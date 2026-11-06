from test_media import env, client, prepare, delete, item, PREFIX
import pytest


@pytest.mark.asyncio
async def test_absent_late_bound_queue_refuses_then_enables(env):
    svc, _ = env
    actual = svc.queue
    class LateQueue:
        target = None
        def __getattr__(self, name):
            if self.target is None: raise AttributeError(name)
            return getattr(self.target, name)
    svc.queue = LateQueue()
    async with await client(svc) as c:
        assert not (await (await c.get(PREFIX+'/capabilities')).json())['delete']
        svc.queue.target = actual
        assert (await (await c.get(PREFIX+'/capabilities')).json())['delete']


@pytest.mark.asyncio
async def test_journal_failure_prevents_unlink(env, monkeypatch):
    svc, tmp = env
    path = tmp/'input/a.png'; path.write_bytes(b'keep')
    async with await client(svc) as c:
        m = await prepare(c,[item()])
        def fail(_): raise OSError('fixture')
        monkeypatch.setattr(svc,'journal',fail)
        _, result = await delete(c,m['token'])
        assert result['items'][0]['status'] == 'failed' and path.exists()


@pytest.mark.asyncio
async def test_untrusted_peer_cannot_spoof_headers(env):
    svc, _ = env
    if svc.auth_mode == 'lan':
        svc.trusted_proxy = '192.0.2.123'
        async with await client(svc) as c:
            assert (await (await c.get(PREFIX+'/capabilities')).json())['delete']
        return
    svc.trusted_proxy = '192.0.2.123'
    async with await client(svc) as c:
        assert (await c.post(PREFIX+'/prepare',json={'items':[item()]})).status == 403
