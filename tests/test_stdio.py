"""Exercise the MCP transport and preview protocol using local test servers."""
from __future__ import annotations

import asyncio
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from websockets.asyncio.server import serve

ROOT = Path(__file__).resolve().parents[1]


class StdioTest(unittest.IsolatedAsyncioTestCase):
    async def test_native_tools_and_errors(self):
        async def adapter(reader, writer):
            request = json.loads(await reader.readline())
            if request == {'operation': 'status', 'token': 'test-token'}:
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
                    'workspace': str(root.resolve()), 'port': port, 'token': 'test-token',
                }), encoding='utf8')
                options = StdioServerParameters(command=sys.executable, args=[str(ROOT / 'server.py')],
                    env={'CUBISM53_WORKSPACE': directory, 'CUBISM53_BRIDGE_STATE': directory,
                         'CUBISM53_BRIDGE_PORT': str(port)})
                async with stdio_client(options) as (reader, writer), ClientSession(reader, writer) as session:
                    await session.initialize()
                    listing = await session.list_tools()
                    self.assertEqual(len({tool.name for tool in listing.tools}), 75)
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
            request = json.loads(await reader.readline())
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
                    'workspace': str(folder), 'port': port, 'token': 'test-token',
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
                self.assertEqual((Path(directory) / 'preview-token.txt').read_text(), 'new-preview-token')


if __name__ == '__main__':
    unittest.main()
