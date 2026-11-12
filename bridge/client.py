"""Local client for the in-process Cubism adapter."""
from __future__ import annotations

import json
import socket

from .settings import bridge_port, state_directory, workspace


def call(operation: str, *, response_timeout: float = 55, **arguments) -> dict:
    path = state_directory() / 'config.json'
    try:
        config = json.loads(path.read_text(encoding='utf8'))
    except FileNotFoundError:
        raise FileNotFoundError('Bridge state is missing. Run cubism_native_connect first.') from None
    if config['port'] != bridge_port() or config['workspace'] != str(workspace()):
        raise RuntimeError('Bridge state belongs to different settings. Choose a separate CUBISM53_BRIDGE_STATE.')
    request = {**arguments, 'operation': operation, 'token': config['token']}
    with socket.create_connection(('127.0.0.1', config['port']), timeout=5) as connection:
        connection.settimeout(response_timeout)
        connection.sendall(json.dumps(request, ensure_ascii=False, allow_nan=False).encode('utf8') + b'\n')
        with connection.makefile('rb') as response_file:
            line = response_file.readline()
    if not line:
        raise ConnectionError('Bridge closed the connection without a reply. Inspect editor state before retrying an edit.')
    response = json.loads(line)
    if not response['ok']:
        raise RuntimeError(response['error'])
    return response['result']


if __name__ == '__main__':
    import sys
    print(json.dumps(call(sys.argv[1] if len(sys.argv) > 1 else 'status'), ensure_ascii=True, indent=2))
