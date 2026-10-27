#!/usr/bin/env python3
"""Personal encrypted project snapshots; loopback only, Python standard library."""
import argparse
import base64
import hashlib
import hmac
import http.server
import importlib.util
import os
from pathlib import Path
import re
import secrets
import socket
import tempfile
import threading
import time

spec = importlib.util.spec_from_file_location('backup_wire', Path(__file__).with_name('publish-server.py'))
wire = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wire)
ROOT = '/api/project-backup/v1'
CHALLENGE = ROOT + '/challenge'
INDEX = ROOT + '/backups'
MAX_FILE = 129 * 1024 * 1024
MAX_TOTAL = 2 * 1024 * 1024 * 1024
MAX_ITEMS = 32
MAGIC = b'ZGBK0001'


def valid_id(value):
    return isinstance(value, str) and re.fullmatch('[a-f0-9]{32}', value)


def validate_record(value, stored=False):
    keys = {'id', 'label', 'bytes', 'sha256'} | ({'createdAt'} if stored else set())
    if not isinstance(value, dict) or set(value) != keys or not valid_id(value['id']):
        raise wire.Rejected('BACKUP_METADATA_INVALID')
    if type(value['bytes']) is not int or not 36 <= value['bytes'] <= MAX_FILE or not isinstance(value['sha256'], str) or not re.fullmatch('[a-f0-9]{64}', value['sha256']):
        raise wire.Rejected('BACKUP_METADATA_INVALID')
    try:
        label = base64.b64decode(value['label'], validate=True)
        if not 28 <= len(label) <= 2048:
            raise ValueError()
    except (ValueError, TypeError):
        raise wire.Rejected('BACKUP_METADATA_INVALID') from None
    if stored and (type(value['createdAt']) is not int or not 0 <= value['createdAt'] <= 9007199254740991):
        raise wire.Rejected('BACKUP_METADATA_INVALID')
    return value


class Store:
    def __init__(self, directory):
        self.directory = Path(directory).absolute()
        wire.common.no_links(self.directory)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.busy = threading.Lock()

    def record(self, identity):
        if not valid_id(identity):
            raise wire.Rejected('BACKUP_ID_INVALID')
        directory = self.directory / identity
        wire.common.no_links(directory)
        if not directory.is_dir():
            raise wire.Rejected('BACKUP_NOT_FOUND', 404)
        if {path.name for path in directory.iterdir()} != {'snapshot.zgb', 'record.json'}:
            raise wire.Rejected('BACKUP_STORAGE_NEEDS_ATTENTION', 409)
        info = wire.regular(directory / 'record.json')
        if info.st_size > 4096:
            raise wire.Rejected('BACKUP_METADATA_INVALID')
        record = validate_record(wire.parse_json((directory / 'record.json').read_bytes()), stored=True)
        if record['id'] != identity or wire.regular(directory / 'snapshot.zgb').st_size != record['bytes']:
            raise wire.Rejected('BACKUP_STORAGE_NEEDS_ATTENTION', 409)
        return record

    def inventory(self):
        names = [path.name for path in self.directory.iterdir()]
        if len(names) > MAX_ITEMS or any(not valid_id(name) for name in names):
            raise wire.Rejected('BACKUP_STORAGE_NEEDS_ATTENTION', 409)
        records = [self.record(name) for name in names]
        used = sum(item['bytes'] for item in records)
        if used > MAX_TOTAL:
            raise wire.Rejected('BACKUP_STORAGE_NEEDS_ATTENTION', 409)
        return sorted(records, key=lambda item: (item['createdAt'], item['id']), reverse=True), used

    def upload(self, stream, metadata, deadline):
        metadata = validate_record(metadata)
        records, used = self.inventory()
        target = self.directory / metadata['id']
        if target.exists():
            raise wire.Rejected('BACKUP_ID_EXISTS', 409)
        if len(records) >= MAX_ITEMS or used + metadata['bytes'] > MAX_TOTAL:
            raise wire.Rejected('BACKUP_CAPACITY', 409)
        staging = Path(tempfile.mkdtemp(prefix='.upload-', dir=self.directory))
        try:
            digest = hashlib.sha256()
            remaining = metadata['bytes']
            with (staging / 'snapshot.zgb').open('xb') as output:
                prefix = bytearray()
                read = getattr(stream, 'read1', stream.read)
                while remaining:
                    if time.monotonic() >= deadline:
                        raise wire.Rejected('BACKUP_TIMEOUT', 408)
                    chunk = read(min(65536, remaining))
                    if time.monotonic() >= deadline:
                        raise wire.Rejected('BACKUP_TIMEOUT', 408)
                    if not chunk:
                        raise wire.Rejected('BACKUP_INCOMPLETE')
                    if len(prefix) < len(MAGIC):
                        prefix.extend(chunk[:len(MAGIC) - len(prefix)])
                        if bytes(prefix) != MAGIC[:len(prefix)]:
                            raise wire.Rejected('BACKUP_ENVELOPE_REQUIRED')
                    output.write(chunk)
                    digest.update(chunk)
                    remaining -= len(chunk)
                output.flush()
                os.fsync(output.fileno())
            if digest.hexdigest() != metadata['sha256']:
                raise wire.Rejected('BACKUP_DIGEST_MISMATCH')
            record = dict(metadata, createdAt=int(time.time() * 1000))
            with (staging / 'record.json').open('xb') as output:
                output.write(wire.encoded(record))
                output.flush()
                os.fsync(output.fileno())
            os.rename(staging, target)
            wire.sync_directory(self.directory)
            return record
        finally:
            if staging.exists():
                for name in ('snapshot.zgb', 'record.json'):
                    (staging / name).unlink(missing_ok=True)
                staging.rmdir()

    def delete(self, identity, digest):
        record = self.record(identity)
        if digest != record['sha256']:
            raise wire.Rejected('BACKUP_CHANGED', 409)
        source = self.directory / identity
        deleted = self.directory / ('.deleting-' + secrets.token_hex(16))
        os.rename(source, deleted)
        for name in ('snapshot.zgb', 'record.json'):
            (deleted / name).unlink()
        deleted.rmdir()
        wire.sync_directory(self.directory)


class Server(wire.Server):
    def __init__(self, port, store, token):
        self.store, self.token = store, token
        self.challenges = wire.Challenges()
        self.slots = threading.BoundedSemaphore(4)
        http.server.ThreadingHTTPServer.__init__(self, ('127.0.0.1', port), Handler)

    def handle_error(self, request, address):
        print('BACKUP_REQUEST_FAILED', flush=True)


class Handler(wire.Handler):
    server_version = 'ZhigouProjectBackup/1'

    def setup(self):
        super().setup()
        self.deadline = time.monotonic() + 300
        def expire():
            try:
                self.request.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        self.expiry = threading.Timer(300, expire)
        self.expiry.daemon = True
        self.expiry.start()

    def finish(self):
        try:
            super().finish()
        finally:
            self.expiry.cancel()

    def authenticate(self):
        self.identity = None
        match = re.fullmatch(re.escape(INDEX) + r'/([a-f0-9]{32})(/delete)?', self.path)
        if self.path not in (CHALLENGE, INDEX) and not match:
            raise wire.Rejected('INVALID_ENDPOINT', 404)
        if match:
            self.identity, self.deleting = match.group(1), bool(match.group(2))
        if self.headers.get_all('Origin') or self.headers.get_all('Authorization') or self.headers.get_all('Transfer-Encoding'):
            raise wire.Rejected('INVALID_HEADERS', 401)
        if sum(len(key) + len(value) for key, value in self.headers.items()) > 16384:
            raise wire.Rejected('HEADERS_TOO_LARGE', 431)
        nonce, ticket, signature, body_sha = [self.header(name) for name in
            ('X-Publish-Nonce', 'X-Publish-Challenge', 'X-Publish-Signature', 'X-Publish-Body-Sha256')]
        if not re.fullmatch('[a-f0-9]{32}', nonce) or not re.fullmatch('[a-f0-9]{64}', signature) or not re.fullmatch('[a-f0-9]{64}', body_sha):
            raise wire.Rejected('SIGNATURE_REQUIRED', 401)
        content_type, length, metadata, revision = [self.header(name, '0' if name == 'Content-Length' else '') for name in
            ('Content-Type', 'Content-Length', 'X-Release-Metadata', 'If-Match')]
        if not re.fullmatch(r'0|[1-9][0-9]{0,9}', length) or len(metadata) > 8192:
            raise wire.Rejected('INVALID_HEADERS')
        expected = wire.request_signature(self.server.token, self.command, self.path, nonce, ticket, content_type, length, metadata, revision, body_sha)
        if not hmac.compare_digest(signature, expected):
            raise wire.Rejected('SIGNATURE_INVALID', 401)
        self.signed_context = (self.command, self.path, nonce, signature)
        self.body_sha = body_sha
        if self.command == 'GET' and (length != '0' or content_type or metadata or body_sha != wire.EMPTY_SHA256):
            raise wire.Rejected('INVALID_READ_REQUEST')
        if self.path == CHALLENGE:
            if self.command != 'GET' or ticket != '-' or revision:
                raise wire.Rejected('INVALID_CHALLENGE_REQUEST')
        else:
            self.server.challenges.consume(nonce, ticket)

    def perform(self, upload):
        acquired = False
        deadline = self.deadline
        try:
            self.authenticate()
            if self.path == CHALLENGE:
                self.reply(200, self.server.challenges.issue(self.signed_context[2]))
                return
            acquired = self.server.store.busy.acquire(False)
            if not acquired:
                raise wire.Rejected('BACKUP_BUSY', 409)
            if self.path == INDEX and not upload:
                if self.header('If-Match'):
                    raise wire.Rejected('INVALID_READ_REQUEST')
                records, used = self.server.store.inventory()
                self.reply(200, {'protocol': 1, 'records': records, 'bytes': used, 'maxItems': MAX_ITEMS, 'maxBytes': MAX_TOTAL})
            elif self.path == INDEX and upload:
                if self.header('Content-Type') != 'application/octet-stream' or self.header('If-Match'):
                    raise wire.Rejected('BACKUP_CONTENT_TYPE_REQUIRED', 415)
                try:
                    metadata = wire.parse_json(base64.b64decode(self.header('X-Release-Metadata'), validate=True))
                except (ValueError, TypeError):
                    raise wire.Rejected('BACKUP_METADATA_INVALID') from None
                validate_record(metadata)
                if metadata['bytes'] != int(self.header('Content-Length')) or metadata['sha256'] != self.body_sha:
                    raise wire.Rejected('BACKUP_METADATA_INVALID')
                record = self.server.store.upload(self.rfile, metadata, deadline)
                self.reply(200, {'ok': True, 'record': record})
            elif self.identity:
                record = self.server.store.record(self.identity)
                if self.header('If-Match') != '"' + record['sha256'] + '"':
                    raise wire.Rejected('BACKUP_CHANGED', 409)
                if self.deleting and upload:
                    if int(self.header('Content-Length', '0')) != 0 or self.header('Content-Type') or self.header('X-Release-Metadata') or self.body_sha != wire.EMPTY_SHA256:
                        raise wire.Rejected('INVALID_DELETE_REQUEST')
                    self.server.store.delete(self.identity, record['sha256'])
                    self.reply(200, {'ok': True, 'id': self.identity})
                elif not self.deleting and not upload:
                    with (self.server.store.directory / self.identity / 'snapshot.zgb').open('rb') as source:
                        digest = hashlib.sha256()
                        while chunk := source.read(65536):
                            if time.monotonic() >= deadline:
                                raise wire.Rejected('BACKUP_TIMEOUT', 408)
                            digest.update(chunk)
                        if digest.hexdigest() != record['sha256']:
                            raise wire.Rejected('BACKUP_DIGEST_MISMATCH')
                        source.seek(0)
                        self.send_response(200)
                        self.send_header('Content-Type', 'application/octet-stream')
                        self.send_header('Content-Length', str(record['bytes']))
                        self.send_header('Cache-Control', 'no-store')
                        self.send_header('Connection', 'close')
                        self.send_header('X-Publish-Signature', wire.mac(self.server.token, 'zhigou-publish-response-v1', *self.signed_context, '200', record['sha256']))
                        self.end_headers()
                        while chunk := source.read(65536):
                            if time.monotonic() >= deadline:
                                return
                            self.wfile.write(chunk)
                else:
                    raise wire.Rejected('INVALID_ENDPOINT', 404)
            else:
                raise wire.Rejected('INVALID_ENDPOINT', 404)
        except wire.Rejected as error:
            self.reply(error.status, {'ok': False, 'code': error.code})
        except (TimeoutError, socket.timeout):
            self.reply(408, {'ok': False, 'code': 'BACKUP_TIMEOUT'})
        except (OSError, ValueError, TypeError):
            self.reply(500, {'ok': False, 'code': 'BACKUP_IO_FAILED'})
        finally:
            if acquired:
                self.server.store.busy.release()
            self.close_connection = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data-dir', type=Path, required=True)
    parser.add_argument('--token-file', type=Path, required=True)
    parser.add_argument('--port', type=int, default=8766)
    args = parser.parse_args()
    os.umask(0o077)
    if not 1024 <= args.port <= 65535 or wire.regular(args.token_file).st_size > 128:
        parser.error('Invalid private configuration')
    token = args.token_file.read_text(encoding='ascii').strip()
    if not re.fullmatch('[a-f0-9]{64}', token):
        parser.error('A separate 64-hex backup key is required')
    store = Store(args.data_dir)
    # Parent contains only the service's process lease; inventory stays in snapshots/.
    with wire.process_lock(store.directory.parent):
        store.inventory()
        server = Server(args.port, store, token)
        print('PROJECT_BACKUP_READY: loopback only; encrypted personal snapshots', flush=True)
        server.serve_forever()


if __name__ == '__main__':
    main()
