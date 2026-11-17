"""Local client for the in-process Cubism adapter."""
from __future__ import annotations

import hmac
import json
import re
import secrets
import socket
import time

from .build import PROTOCOL_VERSION
from .settings import bridge_port, state_directory, workspace


PROTOCOL = f'CUBISM53-MCP/{PROTOCOL_VERSION}'.encode('ascii')
HEX_SHA256 = rb'([0-9a-f]{64})'
SERVER_GREETING = re.compile(rb' '.join((re.escape(PROTOCOL), HEX_SHA256, HEX_SHA256)))
GREETING_TIMEOUT = 5
GREETING_LIMIT = 256
MAX_REQUEST_BYTES = 32 * 1024 * 1024


def read_greeting(connection: socket.socket, deadline: float) -> bytes:
    reply = bytearray()
    while len(reply) < GREETING_LIMIT:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Native bridge authentication timed out')
        connection.settimeout(remaining)
        try:
            chunk = connection.recv(GREETING_LIMIT - len(reply))
        except TimeoutError as error:
            raise TimeoutError('Native bridge authentication timed out') from error
        if not chunk:
            break
        reply.extend(chunk)
        if b'\n' in chunk:
            line, _, trailing = reply.partition(b'\n')
            if not trailing:
                return bytes(line).removesuffix(b'\r')
            break
    raise ConnectionError('Invalid native bridge authentication greeting')


def authenticate(connection: socket.socket, token: str) -> bytes:
    client_nonce = secrets.token_hex(32).encode('ascii')
    deadline = time.monotonic() + GREETING_TIMEOUT
    connection.sendall(PROTOCOL + b' ' + client_nonce + b'\n')
    greeting = read_greeting(connection, deadline)
    match = SERVER_GREETING.fullmatch(greeting)
    if match is None:
        if greeting.startswith(b'{') and b'Unauthorized local client' in greeting:
            raise RuntimeError('Older bridge is running. Use stop from the old package, then reconnect.')
        raise ConnectionError('Invalid native bridge authentication greeting')
    server_nonce, server_mac = match.groups()
    key = token.encode('utf8')

    def proof(role: bytes) -> bytes:
        transcript = b' '.join((PROTOCOL, role, client_nonce, server_nonce))
        return hmac.digest(key, transcript, 'sha256').hex().encode('ascii')

    if not hmac.compare_digest(server_mac, proof(b'server')):
        raise ConnectionError('Native bridge authentication failed; check which service is using the configured port')
    return PROTOCOL + b' ' + proof(b'client') + b'\n'


def call(operation: str, *, response_timeout: float = 55, **arguments) -> dict:
    path = state_directory() / 'config.json'
    try:
        config = json.loads(path.read_text(encoding='utf8'))
    except FileNotFoundError:
        raise FileNotFoundError('Bridge state is missing. Run cubism_native_connect first.') from None
    if config['port'] != bridge_port() or config['workspace'] != str(workspace()):
        raise RuntimeError('Bridge state belongs to different settings. Choose a separate CUBISM53_BRIDGE_STATE.')
    request = json.dumps({**arguments, 'operation': operation}, ensure_ascii=False, allow_nan=False).encode('utf8')
    if len(request) > MAX_REQUEST_BYTES:
        raise ValueError('Native bridge request exceeds 32 MiB')
    with socket.create_connection(('127.0.0.1', config['port']), timeout=GREETING_TIMEOUT) as connection:
        client_proof = authenticate(connection, config['token'])
        connection.settimeout(response_timeout)
        connection.sendall(b''.join((client_proof, request, b'\n')))
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
