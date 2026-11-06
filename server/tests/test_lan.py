from test_media import env, client, prepare, delete, item, PREFIX, MediaService
import pytest


@pytest.mark.asyncio
async def test_lan_one_key_no_proxy_or_principal_and_private_missing(env):
    original, tmp = env
    svc = MediaService(original.roots, original.queue, 'd'*32,
                       tombstone_file=str(tmp/'lan.json'), auth_mode='lan')
    path = tmp/'input/a.png'; path.write_bytes(b'fixture')
    async with await client(svc) as c:
        c.session.headers.pop('X-ComfyChair-Proxy')
        c.session.headers.pop('X-ComfyChair-Principal')
        cap = await (await c.get(PREFIX+'/capabilities')).json()
        assert cap['delete'] and cap['auth_modes'] == ['lan', 'proxy'] and cap['auth_mode'] == 'lan'
        m = await prepare(c, [item()])
        c.session.headers['X-ComfyChair-Principal'] = 'ignored-spoof'
        assert (await delete(c,m['token']))[1]['items'][0]['status'] == 'success'
        assert (await (await c.get(PREFIX+'/missing')).json())['items'] == [item()]
        for key in ('', 'wrong', '中文'):
            c.session.headers['X-ComfyChair-Delete'] = key
            cap = await (await c.get(PREFIX+'/capabilities')).json()
            assert not cap['delete'] and 'items' not in cap
            for route in ('missing',):
                assert (await c.get(PREFIX+'/'+route)).status == 403
            for route, body in [('prepare', {'items':[item()]}), ('delete', {'token':m['token']})]:
                assert (await c.post(PREFIX+'/'+route,json=body)).status == 403


@pytest.mark.asyncio
@pytest.mark.parametrize('mode,key', [('lan',''), ('lan','x'*31), ('unknown','d'*32), ('proxy','d'*32)])
async def test_mode_and_key_fail_closed_without_proxy(env, mode, key):
    original, tmp = env
    svc = MediaService(original.roots, original.queue, key,
                       tombstone_file=str(tmp/'mode.json'), auth_mode=mode)
    async with await client(svc) as c:
        assert not (await (await c.get(PREFIX+'/capabilities')).json())['delete']
        assert (await c.get(PREFIX+'/missing')).status == 403
        assert (await c.post(PREFIX+'/prepare',json={'items':[item()]})).status == 403
