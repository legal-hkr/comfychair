import asyncio
import importlib.util
import json
import os
from pathlib import Path
import threading
from types import SimpleNamespace

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

spec = importlib.util.spec_from_file_location('media_service', Path(__file__).parents[1] / 'comfychair_media/service.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
MediaService = module.MediaService
PREFIX = module.PREFIX


@pytest.fixture(params=['proxy', 'lan'])
def env(tmp_path, request):
    roots = {kind: str(tmp_path / kind) for kind in ('input', 'output')}
    for root in roots.values():
        Path(root).mkdir()
    queue = SimpleNamespace(mutex=threading.RLock(), queue=[], currently_running={})
    svc = MediaService(roots, queue, 'd' * 32, 'p' * 32, '127.0.0.1', str(tmp_path / 'missing.json'), auth_mode=request.param)
    return svc, tmp_path


def item(name='a.png', folder='', kind='input'):
    return {'filename': name, 'subfolder': folder, 'type': kind}


async def client(svc):
    app = web.Application()
    routes = web.RouteTableDef()
    svc.register(routes)
    app.add_routes(routes)
    c = TestClient(TestServer(app))
    await c.start_server()
    c.session.headers.update({'X-ComfyChair-Delete': 'd' * 32, 'X-ComfyChair-Proxy': 'p' * 32,
                              'X-ComfyChair-Principal': 'fixture-user'})
    return c


async def prepare(c, items):
    r = await c.post(PREFIX + '/prepare', json={'items': items})
    assert r.status == 200
    return await r.json()


async def delete(c, token):
    r = await c.post(PREFIX + '/delete', json={'token': token})
    return r.status, await r.json() if r.status == 200 else await r.text()


@pytest.mark.asyncio
async def test_success_idempotency_recreated_name_never_redeleted(env):
    svc, tmp = env
    path = tmp / 'input/a.png'; path.write_bytes(b'image')
    async with await client(svc) as c:
        m = await prepare(c, [item()])
        assert len(m['token']) >= 32
        status, result = await delete(c, m['token'])
        assert status == 200 and result['items'][0]['status'] == 'success' and not path.exists()
        path.write_bytes(b'replacement')
        assert await delete(c, m['token']) == (status, result)
        assert path.read_bytes() == b'replacement'
        assert (await (await c.get(PREFIX + '/missing')).json())['items'] == [item()]
    assert MediaService(svc.roots, svc.queue, 'd'*32, 'p'*32, '127.0.0.1', str(tmp/'missing.json')).tombstones


@pytest.mark.asyncio
@pytest.mark.parametrize('changed', ['replace', 'modify', 'symlink', 'remove'])
async def test_snapshot_preconditions(env, changed):
    svc, tmp = env
    path = tmp / 'input/a.png'; path.write_bytes(b'image')
    async with await client(svc) as c:
        m = await prepare(c, [item()])
        if changed == 'replace':
            other = tmp/'input/other.png'; other.write_bytes(b'new'); other.replace(path)
        elif changed == 'modify': path.write_bytes(b'changed')
        elif changed == 'symlink': path.unlink(); path.symlink_to(tmp/'outside.png')
        else: path.unlink()
        _, result = await delete(c, m['token'])
        assert result['items'][0]['status'] in ('conflict', 'not_found')
        if changed in ('replace', 'modify'): assert path.exists()


@pytest.mark.asyncio
@pytest.mark.parametrize('bad', [item('../a.png'), item('a.png', '..'), item('a.png', '/absolute'), item('a.png', 'a//b'), item('a.exe'), item(kind='temp'), item('a\\b.png'), item('a.png', 'a/./b'), item('a.png\x00'), {'path': 'a.png'}, item('a.png', 'C:')])
async def test_invalid_descriptors(env, bad):
    svc, _ = env
    async with await client(svc) as c:
        m = await prepare(c, [bad])
        assert m['items'][0]['status'] == 'invalid'


@pytest.mark.asyncio
@pytest.mark.parametrize('kind', ['file', 'directory', 'root', 'hardlink'])
async def test_no_links_or_directories(env, kind):
    svc, tmp = env
    outside = tmp/'outside.png'; outside.write_bytes(b'keep')
    path = tmp/'input/a.png'
    descriptor = item()
    if kind == 'file': path.symlink_to(outside)
    elif kind == 'directory':
        (tmp/'input/sub').symlink_to(tmp, target_is_directory=True); descriptor = item('outside.png', 'sub')
    elif kind == 'root':
        (tmp/'alias').symlink_to(tmp/'input', target_is_directory=True); svc.roots['input'] = str(tmp/'alias')
    else: os.link(outside, path)
    async with await client(svc) as c:
        m = await prepare(c, [descriptor]); assert m['items'][0]['status'] in ('invalid', 'not_found')
    assert outside.read_bytes() == b'keep'


@pytest.mark.asyncio
@pytest.mark.parametrize('field', ['secret', 'proxy_secret', 'trusted_proxy', 'tombstone_file'])
async def test_default_deny(env, field):
    svc, tmp = env
    if svc.auth_mode == 'lan' and field in ('proxy_secret', 'trusted_proxy'):
        async with await client(svc) as c:
            assert (await (await c.get(PREFIX+'/capabilities')).json())['delete']
        return  # LAN deliberately has no proxy configuration.
    setattr(svc, field, '')
    svc.ready = False
    (tmp/'input/a.png').write_bytes(b'keep')
    async with await client(svc) as c:
        assert not (await (await c.get(PREFIX+'/capabilities')).json())['delete']
        assert (await c.post(PREFIX+'/prepare', json={'items':[item()]})).status == 403


@pytest.mark.asyncio
@pytest.mark.parametrize('header', ['X-ComfyChair-Delete', 'X-ComfyChair-Proxy', 'X-ComfyChair-Principal'])
async def test_auth_required(env, header):
    svc, _ = env
    if svc.auth_mode == 'lan' and header != 'X-ComfyChair-Delete':
        async with await client(svc) as c:
            c.session.headers[header] = ''
            assert (await (await c.get(PREFIX+'/capabilities')).json())['delete']
        return
    async with await client(svc) as c:
        c.session.headers[header] = ''
        assert (await c.post(PREFIX+'/prepare', json={'items':[item()]})).status == 403


@pytest.mark.asyncio
@pytest.mark.parametrize('phase', ['prepare', 'delete'])
async def test_busy_guard_all_queue_jobs(env, phase):
    svc, tmp = env
    path = tmp/'input/a.png'; path.write_bytes(b'keep')
    async with await client(svc) as c:
        if phase == 'prepare': svc.queue.queue.append(('custom_node_unknown_refs',))
        m = await prepare(c,[item()])
        if phase == 'delete': svc.queue.currently_running['job'] = {}
        _, result = await delete(c,m['token'])
        assert result['items'][0]['status'] == 'busy' and path.exists()


@pytest.mark.asyncio
async def test_ttl_principal_and_unknown_tokens(env):
    svc, tmp = env
    (tmp/'input/a.png').write_bytes(b'keep')
    now = [0]; svc.clock = lambda: now[0]
    async with await client(svc) as c:
        m = await prepare(c,[item()])
        c.session.headers['X-ComfyChair-Principal'] = 'other'
        if svc.auth_mode == 'proxy':
            assert (await delete(c,m['token']))[0] == 409
        c.session.headers['X-ComfyChair-Principal'] = 'fixture-user'
        now[0] = 121
        assert (await delete(c,m['token']))[0] == 409
        assert (await delete(c,'unpredictable-unknown'))[0] == 409


@pytest.mark.asyncio
async def test_batch_and_body_limits(env):
    svc, _ = env
    async with await client(svc) as c:
        for items in ([], [item()]*51):
            assert (await c.post(PREFIX+'/prepare',json={'items':items})).status == 400
        assert (await c.post(PREFIX+'/prepare', data=b'x'*32769)).status == 413
        async def chunks():
            for _ in range(9): yield b'x'*4096
        assert (await c.post(PREFIX+'/prepare',data=chunks())).status == 413
        assert (await c.post(PREFIX+'/prepare',data=b'{')).status == 400


@pytest.mark.asyncio
async def test_partial_failure_and_queue_mutex_is_held(env, monkeypatch):
    svc,tmp=env
    for name in ('a.png','b.png'): (tmp/'input'/name).write_bytes(b'img')
    original = module.os.unlink
    def guarded(path,*args,**kwargs):
        assert svc.queue.mutex._is_owned()
        if path == 'a.png': raise PermissionError()
        return original(path,*args,**kwargs)
    async with await client(svc) as c:
        m=await prepare(c,[item(),item('b.png'),item('missing.png')])
        monkeypatch.setattr(module.os,'unlink',guarded)
        _,r=await delete(c,m['token'])
        assert [v['status'] for v in r['items']] == ['failed','success','not_found']
        assert (tmp/'input/a.png').exists() and not (tmp/'input/b.png').exists()


@pytest.mark.asyncio
async def test_output_nested_and_duplicate(env):
    svc,tmp=env
    (tmp/'output/sub').mkdir(); (tmp/'output/sub/中文 +.png').write_bytes(b'img')
    async with await client(svc) as c:
        value=item('中文 +.png','sub','output')
        m=await prepare(c,[value,value])
        _,r=await delete(c,m['token'])
        assert [v['status'] for v in r['items']] == ['success','invalid']


def test_unconfigured_and_corrupt_journal_fail_closed(env):
    svc,tmp=env
    assert not MediaService(svc.roots,svc.queue).ready
    (tmp/'bad.json').write_text('bad')
    assert not MediaService(svc.roots,svc.queue,'d'*32,'p'*32,'127.0.0.1',str(tmp/'bad.json')).ready
