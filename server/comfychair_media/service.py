"""Linux-only, deliberately narrow physical image deletion service. No workflow nodes."""
import contextlib
import hashlib
import hmac
import json
import os
import secrets
import stat
import threading
import time
from collections import OrderedDict

from aiohttp import web

PREFIX = '/comfychair/v1/media'
MAX_BODY = 32768
EXTENSIONS = {'png', 'jpg', 'jpeg', 'webp', 'bmp', 'gif', 'tif', 'tiff', 'avif'}


class MediaService:
    def __init__(self, roots, queue, secret='', proxy_secret='', trusted_proxy='', tombstone_file=None, clock=time.monotonic, auth_mode='proxy'):
        self.roots = roots
        self.queue = queue
        self.secret = secret
        self.auth_mode = auth_mode
        self.proxy_secret = proxy_secret
        self.trusted_proxy = trusted_proxy
        self.tombstone_file = tombstone_file
        self.clock = clock
        self.lock = threading.RLock()
        self.operations = OrderedDict()
        self.tombstones = set()
        self.ready = bool(len(secret) >= 32 and auth_mode in ('lan', 'proxy') and
                          (auth_mode == 'lan' or (len(proxy_secret) >= 32 and trusted_proxy)) and
                          tombstone_file and os.name == 'posix' and hasattr(os, 'O_NOFOLLOW') and
                          callable(getattr(os, 'unlink', None)))
        if tombstone_file and os.path.exists(tombstone_file):
            try:
                with open(tombstone_file, encoding='utf-8') as f:
                    values = json.load(f)
                if len(values) > 5000:
                    raise ValueError()
                self.tombstones = {self.descriptor(v) for v in values}
            except Exception:
                self.ready = False  # Corrupt consistency journal is not permission to delete.

    @staticmethod
    def descriptor(value):
        if not isinstance(value, dict) or set(value) != {'type', 'subfolder', 'filename'}:
            raise ValueError()
        kind, folder, name = value['type'], value['subfolder'], value['filename']
        if kind not in ('input', 'output') or not isinstance(folder, str) or not isinstance(name, str):
            raise ValueError()
        parts = folder.split('/') + [name] if folder else [name]
        if len(parts) > 32 or any(not p or p in ('.', '..') or len(p.encode()) > 255 or
                                  any(c in p for c in '/\\:') or any(ord(c) < 32 or ord(c) == 127 for c in p)
                                  for p in parts) or name.rsplit('.', 1)[-1].lower() not in EXTENSIONS:
            raise ValueError()
        return kind, folder, name

    @staticmethod
    def public(key):
        return dict(zip(('type', 'subfolder', 'filename'), key))

    @contextlib.contextmanager
    def parent(self, key):
        root = self.roots[key[0]]
        # Walk root too: O_NOFOLLOW on only the last root component is insufficient.
        if not os.path.isabs(root):
            raise ValueError()
        fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            for component in [p for p in root.split('/') if p] + (key[1].split('/') if key[1] else []):
                if component in ('.', '..'):
                    raise ValueError()
                child = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                os.close(fd)
                fd = child
            yield fd
        finally:
            os.close(fd)

    @staticmethod
    def identity(fd, name):
        st = os.stat(name, dir_fd=fd, follow_symlinks=False)
        if not stat.S_ISREG(st.st_mode) or st.st_nlink != 1:
            raise ValueError()  # Refuse hard links too; shared backing objects are out of scope.
        return st.st_dev, st.st_ino, st.st_mtime_ns, st.st_ctime_ns, st.st_size

    def authorized(self, request):
        # Compare bytes: malformed non-ASCII caller headers must deny, not raise TypeError.
        key_ok = hmac.compare_digest(request.headers.get('X-ComfyChair-Delete', '').encode(), self.secret.encode())
        boundary_ok = self.auth_mode == 'lan' or (
            self.auth_mode == 'proxy' and request.remote == self.trusted_proxy and
            hmac.compare_digest(request.headers.get('X-ComfyChair-Proxy', '').encode(), self.proxy_secret.encode()) and
            bool(request.headers.get('X-ComfyChair-Principal', '').strip()))
        return (self.ready and key_ok and boundary_ok and hasattr(self.queue, 'mutex') and
                hasattr(self.queue, 'queue') and hasattr(self.queue, 'currently_running'))

    def principal(self, request):
        # LAN is a shared dedicated-key administrator, never a caller-supplied principal.
        value = self.secret if self.auth_mode == 'lan' else request.headers['X-ComfyChair-Principal']
        return hashlib.sha256((self.auth_mode + ':' + value).encode()).digest()

    def busy(self):
        # Caller holds ComfyUI's actual prompt queue mutex, not a /queue HTTP snapshot.
        return bool(self.queue.queue or self.queue.currently_running)

    def prune(self):
        now = self.clock()
        for token, op in list(self.operations.items()):
            if now > op['retain']:
                del self.operations[token]

    async def body(self, request):
        if request.content_length is not None and request.content_length > MAX_BODY:
            raise web.HTTPRequestEntityTooLarge(max_size=MAX_BODY, actual_size=request.content_length)
        data = bytearray()
        async for chunk in request.content.iter_chunked(4096):
            data.extend(chunk)
            if len(data) > MAX_BODY:
                raise web.HTTPRequestEntityTooLarge(max_size=MAX_BODY, actual_size=len(data))
        try:
            obj = json.loads(data)
            if not isinstance(obj, dict):
                raise ValueError()
            return obj
        except (ValueError, UnicodeError):
            raise web.HTTPBadRequest(text='invalid body')

    async def capability(self, request):
        return web.json_response({'version': 1, 'delete': bool(self.authorized(request)), 'max_batch': 50,
                                  'auth_modes': ['lan', 'proxy'], 'auth_mode': self.auth_mode,
                                  'permanent': True, 'consistency': 'persistent_tombstones', 'ttl_seconds': 120})

    async def missing(self, request):
        if not self.authorized(request):
            raise web.HTTPForbidden(text='delete unavailable')
        # Private descriptors require the same dedicated key as deletion.
        with self.lock:
            return web.json_response({'items': [self.public(k) for k in sorted(self.tombstones)]})

    async def prepare(self, request):
        if not self.authorized(request):
            raise web.HTTPForbidden(text='delete unavailable')
        body = await self.body(request)
        items = body.get('items')
        if set(body) != {'items'} or not isinstance(items, list) or not 1 <= len(items) <= 50:
            raise web.HTTPBadRequest(text='invalid batch')
        with self.lock, self.queue.mutex:
            self.prune()
            if len(self.operations) >= 256 or len(self.tombstones) + len(items) > 5000:
                raise web.HTTPServiceUnavailable(text='capacity')
            manifest = []
            seen = set()
            for index, item in enumerate(items):
                result = {'index': index, 'status': 'invalid'}
                entry = {'result': result}
                try:
                    key = self.descriptor(item)
                    if key in seen:
                        raise ValueError()
                    seen.add(key)
                    entry['key'] = key
                    result['item'] = self.public(key)
                    if self.busy():
                        result['status'] = 'busy'
                    else:
                        with self.parent(key) as fd:
                            entry['stat'] = self.identity(fd, key[2])
                        result['status'] = 'ready'
                except FileNotFoundError:
                    result['status'] = 'not_found'
                except (ValueError, OSError, KeyError):
                    pass
                manifest.append(entry)
            token = secrets.token_urlsafe(32)
            self.operations[token] = {'principal': self.principal(request), 'expires': self.clock() + 120,
                                      'retain': self.clock() + 600, 'manifest': manifest, 'results': None}
            return web.json_response({'token': token, 'items': [m['result'] for m in manifest], 'ttl_seconds': 120})

    def journal(self, key):
        # Write intent BEFORE unlink. A failed delete may remain hidden until administrator reindex.
        updated = self.tombstones | {key}
        target = self.tombstone_file
        temporary = target + '.' + secrets.token_hex(8) + '.tmp'
        try:
            fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(fd, 'w', encoding='utf-8') as f:
                json.dump([self.public(k) for k in sorted(updated)], f)
                f.flush()
                os.fsync(f.fileno())
            os.replace(temporary, target)
            parent = os.open(os.path.dirname(target), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                os.fsync(parent)
            finally:
                os.close(parent)
            self.tombstones = updated
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)

    async def delete(self, request):
        if not self.authorized(request):
            raise web.HTTPForbidden(text='delete unavailable')
        body = await self.body(request)
        if set(body) != {'token'} or not isinstance(body['token'], str) or len(body['token']) > 128:
            raise web.HTTPBadRequest(text='invalid token')
        with self.lock, self.queue.mutex:
            self.prune()
            op = self.operations.get(body['token'])
            if not op or not hmac.compare_digest(op['principal'], self.principal(request)):
                raise web.HTTPConflict(text='unknown token')
            if op['results'] is not None:
                return web.json_response({'items': op['results']})
            if self.clock() > op['expires']:
                raise web.HTTPConflict(text='expired token')
            results = []
            for entry in op['manifest']:
                result = dict(entry['result'])
                if result['status'] == 'ready':
                    key = entry['key']
                    try:
                        if self.busy():
                            result['status'] = 'busy'
                        else:
                            with self.parent(key) as fd:
                                if self.identity(fd, key[2]) != entry['stat']:
                                    result['status'] = 'conflict'
                                else:
                                    self.journal(key)
                                    # Recheck after journal I/O. External writers still need exclusion.
                                    if self.identity(fd, key[2]) != entry['stat']:
                                        result['status'] = 'conflict'
                                    else:
                                        os.unlink(key[2], dir_fd=fd)
                                        result['status'] = 'success'
                    except FileNotFoundError:
                        result['status'] = 'not_found'
                    except ValueError:
                        result['status'] = 'conflict'
                    except OSError:
                        result['status'] = 'failed'
                results.append(result)
            op['results'] = results  # Same token never executes again, including busy/failed outcomes.
            return web.json_response({'items': results})

    def register(self, routes):
        routes.get(PREFIX + '/capabilities')(self.capability)
        routes.get(PREFIX + '/missing')(self.missing)
        routes.post(PREFIX + '/prepare')(self.prepare)
        routes.post(PREFIX + '/delete')(self.delete)
