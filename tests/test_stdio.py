"""Exercise the MCP transport and preview protocol using local test servers."""
from __future__ import annotations

import asyncio
import hmac
from http import HTTPStatus
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from websockets.asyncio.server import serve

ROOT = Path(__file__).resolve().parents[1]
NATIVE_PROTOCOL = b'CUBISM53-MCP/3'
NATIVE_TOKEN = 'test-token'
SERVER_NONCE = b'21' * 32


def bridge_mac(role, client_nonce):
    transcript = b' '.join((NATIVE_PROTOCOL, role, client_nonce, SERVER_NONCE))
    return hmac.digest(NATIVE_TOKEN.encode('utf8'), transcript, 'sha256').hex().encode('ascii')


async def native_request(reader, writer):
    protocol, client_nonce = (await reader.readline()).rstrip(b'\n').split(b' ')
    assert protocol == NATIVE_PROTOCOL and len(bytes.fromhex(client_nonce.decode('ascii'))) == 32
    writer.write(b' '.join((protocol, SERVER_NONCE, bridge_mac(b'server', client_nonce))) + b'\n')
    await writer.drain()
    assert await reader.readline() == b' '.join((protocol, bridge_mac(b'client', client_nonce))) + b'\n'
    request = json.loads(await reader.readline())
    assert 'token' not in request
    return request


class StdioTest(unittest.IsolatedAsyncioTestCase):
    async def test_native_tools_and_errors(self):
        async def adapter(reader, writer):
            request = await native_request(reader, writer)
            if request == {'operation': 'status'}:
                response = {'ok': True, 'result': {'documents': [], 'editor_thread': True}}
            elif request.get('document_uid') == 'missing':
                response = {'ok': False, 'error': 'Document not found: missing'}
            else:
                response = None
            if response is not None:
                writer.write(json.dumps(response).encode() + b'\n')
                await writer.drain()
            writer.close()
            await writer.wait_closed()

        async with await asyncio.start_server(adapter, '127.0.0.1', 0) as listener:
            port = listener.sockets[0].getsockname()[1]
            with TemporaryDirectory() as directory:
                root = Path(directory)
                (root / 'config.json').write_text(json.dumps({
                    'workspace': str(root.resolve()), 'port': port, 'token': NATIVE_TOKEN,
                }), encoding='utf8')
                options = StdioServerParameters(command=sys.executable, args=[str(ROOT / 'server.py')],
                    env={'CUBISM53_WORKSPACE': directory, 'CUBISM53_BRIDGE_STATE': directory,
                         'CUBISM53_BRIDGE_PORT': str(port)})
                async with stdio_client(options) as (reader, writer), ClientSession(reader, writer) as session:
                    await session.initialize()
                    listing = await session.list_tools()
                    self.assertEqual(len({tool.name for tool in listing.tools}), 93)
                    self.assertIsNone(listing.nextCursor)
                    result = await session.call_tool('cubism_native_status', {})
                    self.assertTrue(result.structuredContent['editor_thread'])
                    for uid, error in [('missing', 'Document not found'), ('closed', 'without a reply')]:
                        result = await session.call_tool('cubism_native_document', {'document_uid': uid})
                        self.assertTrue(result.isError)
                        self.assertIn(error, result.content[0].text)

    async def test_job_status_has_a_reserved_channel(self):
        release = asyncio.Event()
        saturated = asyncio.Event()
        ordinary = 0

        async def adapter(reader, writer):
            nonlocal ordinary
            request = await native_request(reader, writer)
            if request['operation'] == 'status':
                ordinary += 1
                if ordinary == 8:
                    saturated.set()
                await release.wait()
                result = {'documents': []}
            else:
                result = {'state': 'queued'}
            writer.write(json.dumps({'ok': True, 'result': result}).encode() + b'\n')
            await writer.drain()
            writer.close()
            await writer.wait_closed()

        async with await asyncio.start_server(adapter, '127.0.0.1', 0) as listener:
            port = listener.sockets[0].getsockname()[1]
            with TemporaryDirectory() as directory:
                folder = Path(directory).resolve()
                (folder / 'config.json').write_text(json.dumps({
                    'workspace': str(folder), 'port': port, 'token': NATIVE_TOKEN,
                }), encoding='utf8')
                options = StdioServerParameters(command=sys.executable, args=[str(ROOT / 'server.py')],
                    env={'CUBISM53_WORKSPACE': str(folder), 'CUBISM53_BRIDGE_STATE': str(folder),
                         'CUBISM53_BRIDGE_PORT': str(port), 'PYTHONDONTWRITEBYTECODE': '1'})
                async with stdio_client(options) as (reader, writer), ClientSession(reader, writer) as session:
                    await session.initialize()
                    calls = [asyncio.create_task(session.call_tool('cubism_native_status', {})) for _ in range(24)]
                    try:
                        await asyncio.wait_for(saturated.wait(), 5)
                        result = await asyncio.wait_for(
                            session.call_tool('cubism_native_job_status', {'job_id': 'test'}), 1)
                        self.assertEqual(result.structuredContent['state'], 'queued')
                        done, pending = await asyncio.wait(calls, timeout=3, return_when=asyncio.FIRST_COMPLETED)
                        for _ in range(24):
                            if len(done) >= 16:
                                break
                            more, pending = await asyncio.wait(pending, timeout=.15, return_when=asyncio.FIRST_COMPLETED)
                            done.update(more)
                        self.assertEqual(len(done), 16)
                        self.assertEqual(ordinary, 8)
                        for call in done:
                            self.assertTrue(call.result().isError)
                            self.assertIn('busy', call.result().content[0].text)
                    finally:
                        release.set()
                        await asyncio.gather(*calls)

    async def test_native_client_rejects_replayed_proof_and_legacy_server_without_sending_arguments(self):
        observed = []
        greetings = iter([
            b' '.join((NATIVE_PROTOCOL, SERVER_NONCE, bridge_mac(b'server', b'00' * 32))) + b'\n',
            b'{"ok":false,"error":"java.lang.SecurityException: Unauthorized local client"}\n',
        ])

        async def other_listener(reader, writer):
            try:
                hello = await reader.readline()
                writer.write(next(greetings))
                await writer.drain()
                observed.append((hello, await reader.read()))
            finally:
                writer.close()
                await writer.wait_closed()

        async with await asyncio.start_server(other_listener, '127.0.0.1', 0) as listener:
            port = listener.sockets[0].getsockname()[1]
            with TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                (root / 'config.json').write_text(json.dumps({
                    'workspace': str(root), 'port': port, 'token': NATIVE_TOKEN,
                }), encoding='utf8')
                options = StdioServerParameters(command=sys.executable, args=[str(ROOT / 'server.py')],
                    env={'CUBISM53_WORKSPACE': str(root), 'CUBISM53_BRIDGE_STATE': str(root),
                         'CUBISM53_BRIDGE_PORT': str(port)})
                async with stdio_client(options) as (reader, writer), ClientSession(reader, writer) as session:
                    await session.initialize()
                    for expected in ('authentication failed', 'Older bridge is running'):
                        result = await session.call_tool('cubism_native_import_psd', {'path': 'private-assets.psd'})
                        self.assertTrue(result.isError)
                        self.assertIn(expected, result.content[0].text)
                self.assertEqual(len(observed), 2)
                for hello, rest in observed:
                    self.assertRegex(hello, rb'^CUBISM53-MCP/3 [0-9a-f]{64}\n$')
                    self.assertEqual(rest, b'')
                self.assertNotEqual(observed[0][0], observed[1][0])

    async def test_preview_recovers_after_registration_failure(self):
        methods = []

        async def editor(socket):
            async for raw in socket:
                request = json.loads(raw)
                method = request['Method']
                methods.append(method)
                if method == 'RegisterPlugin' and len(methods) == 1:
                    response = {'Type': 'Error', 'Data': {'Reason': 'test rejection'}}
                else:
                    data = {
                        'RegisterPlugin': {'Token': 'new-preview-token'},
                        'GetIsApproval': {'Result': True},
                        'GetDocument': {'ModelUID': 'model-1'},
                    }.get(method, request['Data'])
                    response = {'Type': 'Response', 'Data': data}
                await socket.send(json.dumps({**response, 'RequestId': request['RequestId']}))

        async with serve(editor, '127.0.0.1', 0) as listener:
            port = listener.sockets[0].getsockname()[1]
            with TemporaryDirectory() as directory:
                options = StdioServerParameters(command=sys.executable, args=[str(ROOT / 'server.py')],
                    env={'CUBISM53_BRIDGE_STATE': directory, 'CUBISM53_PORT': str(port)})
                async with stdio_client(options) as (reader, writer), ClientSession(reader, writer) as session:
                    await session.initialize()
                    arguments = {'document_uid': 'doc-1', 'parameters': {'ParamAngleX': 10}}
                    first = await session.call_tool('cubism_native_set_preview_parameters', arguments)
                    self.assertTrue(first.isError)
                    second = await session.call_tool('cubism_native_set_preview_parameters', arguments)
                    self.assertFalse(second.isError)
                    self.assertEqual(second.structuredContent['result'], {
                        'ModelUID': 'model-1', 'Parameters': [{'Id': 'ParamAngleX', 'Value': 10}],
                    })
                    cleared = await session.call_tool('cubism_native_clear_preview_parameters',
                                                     {'document_uid': 'doc-1'})
                    self.assertEqual(cleared.structuredContent['result'], {'ModelUID': 'model-1'})
                self.assertEqual(methods.count('RegisterPlugin'), 2)
                self.assertEqual((Path(directory) / f'preview-{port}-token.txt').read_text(), 'new-preview-token')

    async def test_concurrent_clients_share_token_only_for_the_same_port(self):
        calls = {'a': [], 'b': []}

        def server(endpoint):
            async def handler(socket):
                async for raw in socket:
                    request = json.loads(raw)
                    method = request['Method']
                    if method == 'RegisterPlugin':
                        calls[endpoint].append(request['Data']['Token'])
                        await asyncio.sleep(0.15)
                    response = {'RegisterPlugin': {'Token': f'token-{endpoint}'},
                                'GetIsApproval': {'Result': True}, 'GetDocument': {'ModelUID': 'model'}}.get(method, {})
                    await socket.send(json.dumps({'Type': 'Response', 'RequestId': request['RequestId'], 'Data': response}))
            return handler

        async with serve(server('a'), '127.0.0.1', 0) as a, serve(server('b'), '127.0.0.1', 0) as b:
            with TemporaryDirectory() as directory:
                async def preview(endpoint):
                    port = endpoint.sockets[0].getsockname()[1]
                    options = StdioServerParameters(command=sys.executable, args=[str(ROOT / 'server.py')],
                        env={'CUBISM53_BRIDGE_STATE': directory, 'CUBISM53_PORT': str(port)})
                    async with stdio_client(options) as (reader, writer), ClientSession(reader, writer) as session:
                        await session.initialize()
                        result = await session.call_tool('cubism_native_clear_preview_parameters', {'document_uid': 'doc'})
                        self.assertFalse(result.isError, result.content)

                await asyncio.gather(preview(a), preview(a), preview(b))
                self.assertEqual(calls, {'a': ['', 'token-a'], 'b': ['']})

    async def test_preview_does_not_follow_redirect_to_another_endpoint(self):
        received = []
        connections = 0

        async def other_endpoint(socket):
            nonlocal connections
            connections += 1
            async for raw in socket:
                request = json.loads(raw)
                received.append(request)
                data = {'RegisterPlugin': {'Token': 'replacement-token'},
                        'GetIsApproval': {'Result': True},
                        'GetDocument': {'ModelUID': 'other-model'}}.get(request['Method'], {})
                await socket.send(json.dumps({'Type': 'Response', 'RequestId': request['RequestId'], 'Data': data}))

        async with serve(other_endpoint, '127.0.0.1', 0) as other:
            other_port = other.sockets[0].getsockname()[1]

            def redirect(socket, _request):
                response = socket.respond(HTTPStatus.TEMPORARY_REDIRECT, 'Redirect')
                response.headers['Location'] = f'ws://127.0.0.1:{other_port}'
                return response

            async with serve(other_endpoint, '127.0.0.1', 0, process_request=redirect) as listener:
                port = listener.sockets[0].getsockname()[1]
                with TemporaryDirectory() as directory:
                    token_path = Path(directory) / f'preview-{port}-token.txt'
                    token_path.write_text('approved-token', encoding='utf8')
                    options = StdioServerParameters(command=sys.executable, args=[str(ROOT / 'server.py')],
                        env={'CUBISM53_BRIDGE_STATE': directory, 'CUBISM53_PORT': str(port)})
                    async with stdio_client(options) as (reader, writer), ClientSession(reader, writer) as session:
                        await session.initialize()
                        result = await session.call_tool('cubism_native_list_preview_views', {'document_uid': 'doc'})
                    self.assertEqual(received, [])
                    self.assertEqual(connections, 0)
                    self.assertTrue(result.isError)
                    self.assertEqual(token_path.read_text(encoding='utf8'), 'approved-token')


if __name__ == '__main__':
    unittest.main()
