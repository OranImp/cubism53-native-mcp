"""Temporary canvas preview via the official local Cubism WebSocket API."""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
import json
import uuid

from websockets.asyncio.client import connect

from .settings import port_from_env, state_directory, write_text_atomic

PLUGIN_NAME = 'Cubism53 Native MCP'


class CubismAPIError(RuntimeError):
    pass


class CanvasPreview:
    def __init__(self):
        self.port = port_from_env('CUBISM53_PORT', 22033)
        self.token_path = state_directory() / f'preview-{self.port}-token.txt'
        self.socket = None
        self.reader = None
        self.pending = {}
        self.connection_lock = asyncio.Lock()

    async def _receive(self, socket):
        try:
            async for raw in socket:
                message = json.loads(raw)
                future = self.pending.get(message.get('RequestId'))
                if future is None or future.done():
                    continue
                if message.get('Type') == 'Error':
                    future.set_exception(CubismAPIError(json.dumps(message.get('Data', {}), ensure_ascii=False)))
                elif message.get('Type') == 'Response':
                    future.set_result(message.get('Data', {}))
        finally:
            for future in self.pending.values():
                if not future.done():
                    future.set_exception(ConnectionError('Cubism preview connection closed'))
            await socket.close()

    async def rpc(self, method, data=None, timeout=15):
        socket = self.socket
        if socket is None:
            raise ConnectionError('Cubism preview is not connected')
        request_id = uuid.uuid4().hex
        future = asyncio.get_running_loop().create_future()
        self.pending[request_id] = future
        try:
            await socket.send(json.dumps({'Version': '1.0.0', 'RequestId': request_id, 'Type': 'Request',
                                          'Method': method, 'Data': data or {}}))
            return await asyncio.wait_for(future, timeout)
        finally:
            self.pending.pop(request_id, None)
            if future.done() and not future.cancelled():
                future.exception()

    async def connect(self):
        async with self.connection_lock:
            if self.socket is not None and self.socket.close_code is None and not self.reader.done():
                return
            await self._close()
            self.socket = await connect(f'ws://127.0.0.1:{self.port}', proxy=None, open_timeout=5)
            self.reader = asyncio.create_task(self._receive(self.socket))
            try:
                async with self.registration_lock():
                    token = self.token_path.read_text(encoding='utf8').strip() if self.token_path.exists() else ''
                    response = await self.rpc('RegisterPlugin', {'Token': token, 'Name': PLUGIN_NAME})
                    if response.get('Token'):
                        write_text_atomic(self.token_path, response['Token'])
            except BaseException:
                await self._close()
                raise

    @asynccontextmanager
    async def registration_lock(self):
        import msvcrt

        self.token_path.parent.mkdir(parents=True, exist_ok=True)
        with self.token_path.with_suffix('.lock').open('a+b') as stream:
            try:
                async with asyncio.timeout(20):
                    while True:
                        stream.seek(0)
                        try:
                            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                            break
                        except OSError:
                            await asyncio.sleep(0.1)
            except TimeoutError as error:
                raise RuntimeError('Timed out waiting for another preview registration') from error
            try:
                yield
            finally:
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)

    async def ready(self):
        await self.connect()
        approval = await self.rpc('GetIsApproval')
        if not approval.get('Result'):
            raise PermissionError(f'In Cubism External Application Integration, approve {PLUGIN_NAME} while MCP stays running, then retry.')

    async def _close(self):
        if self.socket is not None:
            await self.socket.close()
        if self.reader is not None:
            await asyncio.gather(self.reader, return_exceptions=True)
        self.socket = self.reader = None

    async def close(self):
        async with self.connection_lock:
            await self._close()
