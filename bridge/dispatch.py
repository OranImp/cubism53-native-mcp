"""Reserve workers for job status while native editor calls are waiting."""
from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
from functools import partial


class RequestChannel:
    def __init__(self, workers: int, name: str):
        self.limit = workers
        self.pending = 0
        self.executor = ThreadPoolExecutor(max_workers=workers, thread_name_prefix=name)

    async def run(self, function, /, *args, **kwargs):
        if self.pending >= self.limit:
            raise RuntimeError('MCP request channel is busy; retry the request')
        loop = asyncio.get_running_loop()
        result = loop.run_in_executor(self.executor, partial(function, *args, **kwargs))
        self.pending += 1

        def completed(future):
            self.pending -= 1
            if not future.cancelled():
                future.exception()

        result.add_done_callback(completed)
        # Cancelling the MCP await does not stop a synchronous native call.
        return await asyncio.shield(result)

    def close(self):
        self.executor.shutdown(wait=False)
