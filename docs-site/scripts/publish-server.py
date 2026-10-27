#!/usr/bin/env python3
"""HMAC-authenticated loopback APK publisher. Python 3.9+, no third-party packages."""
import argparse
import base64
from contextlib import contextmanager
import hashlib
import hmac
import http.server
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import stat
import struct
import tempfile
import threading
import time
import zipfile

spec = importlib.util.spec_from_file_location('zhigou_deploy', Path(__file__).with_name('deploy-static.py'))
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)
MAX_BYTES = 448 * 1024 * 1024
CERTIFICATE = '8e48ea72d827f96ea57c6b7bf4d566bac7581fa23106324bf3296fa9e2024f6c'
LEAF_KEYS = {'owner', 'available', 'applicationId', 'variant', 'profile', 'status', 'certificateSha256',
             'versionName', 'versionCode', 'minSdk', 'abis', 'bytes', 'sha256', 'builtAt', 'url', 'checksumUrl', 'description'}
MATCH_KEYS = ['versionName', 'versionCode', 'variant', 'profile', 'minSdk', 'status', 'certificateSha256']
API_ROOT = '/api/publisher/v1'
CHALLENGE_PATH = API_ROOT + '/challenge'
RELEASE_PATH = API_ROOT + '/releases'
ARCHIVE_PATH = API_ROOT + '/archives'
MAX_ARCHIVES = 32
MAX_ARCHIVE_BYTES = 4 * 1024 * 1024 * 1024
EMPTY_SHA256 = hashlib.sha256(b'').hexdigest()
CHALLENGE_SECONDS = 120


class Rejected(Exception):
    def __init__(self, code, status=400):
        self.code, self.status = code, status


def mac(key, *parts):
    return hmac.new(key.encode('ascii'), ('\n'.join(parts) + '\n').encode('ascii'), hashlib.sha256).hexdigest()


def request_signature(key, method, path, nonce, challenge, content_type='', length='0', metadata='', revision='', body_sha=EMPTY_SHA256):
    return mac(key, 'zhigou-publish-request-v1', method, path, nonce, challenge,
               content_type, length, hashlib.sha256(metadata.encode('ascii')).hexdigest(), revision, body_sha)


def response_signature(key, context, status, body):
    return mac(key, 'zhigou-publish-response-v1', *context, str(status), hashlib.sha256(body).hexdigest())


class Challenges:
    def __init__(self):
        self.entries = {}
        self.lock = threading.Lock()

    def issue(self, nonce):
        with self.lock:
            now = time.monotonic()
            self.entries = {key: value for key, value in self.entries.items() if value[1] > now}
            if nonce in self.entries:
                raise Rejected('NONCE_REPLAY', 409)
            if len(self.entries) >= 64:
                raise Rejected('CHALLENGE_CAPACITY', 429)
            ticket = secrets.token_hex(32)
            self.entries[nonce] = (ticket, now + CHALLENGE_SECONDS, False)
            return {'protocol': 1, 'nonce': nonce, 'challenge': ticket,
                    'issuedAt': int(time.time()), 'validForSeconds': CHALLENGE_SECONDS}

    def consume(self, nonce, ticket):
        with self.lock:
            entry = self.entries.get(nonce)
            if not entry or entry[1] <= time.monotonic() or not hmac.compare_digest(entry[0], ticket):
                raise Rejected('CHALLENGE_INVALID', 401)
            if entry[2]:
                raise Rejected('CHALLENGE_REPLAY', 409)
            self.entries[nonce] = (entry[0], entry[1], True)


def encoded(value):
    return (json.dumps(value, ensure_ascii=True, indent=2, allow_nan=False) + '\n').encode('utf-8')


def parse_json(value):
    def unique(pairs):
        result = {}
        for key, item in pairs:
            if key in result:
                raise Rejected('INVALID_JSON')
            result[key] = item
        return result
    try:
        return json.loads(value, object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(Rejected('INVALID_JSON')))
    except (ValueError, UnicodeError, RecursionError) as error:
        raise Rejected('INVALID_JSON') from error


def regular(path):
    common.no_links(path)
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        raise Rejected('UNSAFE_FILE', 409)
    return info


def validate_leaf(item):
    try:
        common.validate_download(item)
        if set(item) - LEAF_KEYS or not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z', item.get('builtAt', '')):
            raise ValueError('Invalid fields')
    except (ValueError, TypeError, KeyError) as error:
        raise Rejected('INVALID_RELEASE') from error


def leaves(manifest):
    try:
        if isinstance(manifest, dict) and isinstance(manifest.get('history', []), list) and isinstance(manifest.get('alternatives', []), list) and len(manifest.get('history', [])) + len(manifest.get('alternatives', [])) >= 8:
            raise Rejected('HISTORY_BUDGET_EXCEEDED', 409)
        result = common.releases_of(manifest)
        for item in result:
            validate_leaf(item)
        if len({item['url'] for item in result}) != len(result):
            raise Rejected('DUPLICATE_RELEASE')
        if sum(item['bytes'] for item in result) > MAX_BYTES:
            raise Rejected('HISTORY_BUDGET_EXCEEDED', 409)
        abis = set(manifest['abis'])
        for item in manifest.get('alternatives', []):
            if any(item.get(key) != manifest.get(key) for key in MATCH_KEYS) or abis.intersection(item['abis']):
                raise Rejected('ALTERNATIVE_MISMATCH', 409)
            abis.update(item['abis'])
        return result
    except (ValueError, TypeError, KeyError) as error:
        raise Rejected('INVALID_MANIFEST') from error


def read_manifest(directory, verify_files=False):
    path = directory / 'release.json'
    if regular(path).st_size > 65536:
        raise Rejected('MANIFEST_TOO_LARGE')
    raw = path.read_bytes()
    value = parse_json(raw)
    items = [] if value == {'available': False} else leaves(value)
    expected = {'release.json'} | {Path(item['url']).name + suffix for item in items for suffix in ['', '.sha256']}
    if {item.name for item in directory.iterdir()} != expected:
        raise Rejected('UNKNOWN_DOWNLOAD_FILES', 409)
    for item in items:
        apk = directory / Path(item['url']).name
        checksum = directory / Path(item['checksumUrl']).name
        regular(apk)
        regular(checksum)
        if verify_files:
            try:
                common.verify_download(directory.parent, item, None)
            except (ValueError, OSError) as error:
                raise Rejected('EXISTING_DOWNLOAD_INVALID', 409) from error
    return value, '"' + hashlib.sha256(raw).hexdigest() + '"'


def apk_abis(path):
    # Bound the central directory before asking zipfile to allocate its entries.
    size = regular(path).st_size
    with path.open('rb') as source:
        source.seek(max(0, size - 65557))
        tail = source.read(65557)
    end = next((i for i in range(len(tail) - 22, -1, -1) if tail[i:i+4] == b'PK\x05\x06'
                and i + 22 + struct.unpack_from('<H', tail, i + 20)[0] == len(tail)), None)
    if end is None:
        raise Rejected('INVALID_APK_ZIP')
    _, disk, start, count1, count2, length, offset, _ = struct.unpack_from('<4s4H2IH', tail, end)
    if disk or start or not count1 or count1 != count2 or count1 == 65535 or length > 16 * 1024 * 1024 or offset + length != size - len(tail) + end:
        raise Rejected('INVALID_APK_ZIP')
    try:
        with zipfile.ZipFile(path) as apk:
            names = [info.filename for info in apk.infolist()]
        if len(names) != count1 or len(set(names)) != len(names):
            raise Rejected('INVALID_APK_ZIP')
        abis = sorted({name.split('/')[1] for name in names if re.fullmatch(r'lib/[^/]+/libengine_jni\.so', name)})
        if not abis or any(abi not in ['arm64-v8a', 'x86_64'] for abi in abis):
            raise Rejected('INVALID_APK_ABI')
        required = {'AndroidManifest.xml', 'assets/vision/en_PP-OCRv4_rec_mobile.onnx', 'assets/vision/en_PP-OCRv5_rec_mobile.onnx'}
        required.update('lib/' + abi + '/' + name for abi in abis for name in ['libopencv_java4.so', 'libonnxruntime.so', 'libonnxruntime4j_jni.so'])
        if not required.issubset(names):
            raise Rejected('INVALID_APK_PROFILE')
        return abis
    except (zipfile.BadZipFile, OSError, UnicodeError) as error:
        raise Rejected('INVALID_APK_ZIP') from error


def next_manifest(previous, incoming, alternative):
    validate_leaf(incoming)
    known = leaves(previous) if previous.get('available') else []
    if known and incoming['versionCode'] < previous['versionCode']:
        raise Rejected('VERSION_DOWNGRADE', 409)
    existing = next((item for item in known if item['url'] == incoming['url']), None)
    if existing and any(existing.get(key) != incoming.get(key) for key in MATCH_KEYS + ['sha256', 'bytes', 'abis']):
        raise Rejected('IMMUTABLE_RELEASE_CONFLICT', 409)
    current = dict(incoming)
    if existing:
        current['builtAt'] = existing['builtAt']
    primary = {key: value for key, value in previous.items() if key not in ['history', 'alternatives']}
    if alternative:
        if not known:
            raise Rejected('ALTERNATIVE_REQUIRES_PRIMARY', 409)
        alternatives = [item for item in previous.get('alternatives', []) if item['url'] != current['url']] + [current]
    else:
        alternatives = previous.get('alternatives', []) if previous.get('url') == current['url'] else []
        primary = current
    active = {item['url'] for item in [primary] + alternatives}
    result = dict(primary, history=[item for item in known if item['url'] not in active])
    if alternatives:
        result['alternatives'] = alternatives
    leaves(result)
    if len(encoded(result)) > 65536:
        raise Rejected('MANIFEST_TOO_LARGE')
    return result


def sync_directory(path):
    if os.name == 'posix':
        fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def archive_selection(value):
    if not isinstance(value, dict) or set(value) != {'formatVersion', 'sha256'} or type(value['formatVersion']) is not int or value['formatVersion'] != 1:
        raise Rejected('INVALID_ARCHIVE_SELECTION')
    selected = value['sha256']
    if not isinstance(selected, list) or not 1 <= len(selected) <= 8 or any(not isinstance(item, str) or not re.fullmatch('[a-f0-9]{64}', item) for item in selected):
        raise Rejected('INVALID_ARCHIVE_SELECTION')
    if len(set(selected)) != len(selected):
        raise Rejected('DUPLICATE_ARCHIVE_SELECTION')
    return selected


def read_archive_body(source, length):
    deadline = time.monotonic() + 15
    body = bytearray()
    read = getattr(source, 'read1', source.read)
    while len(body) < length:
        if time.monotonic() >= deadline:
            raise Rejected('ARCHIVE_READ_TIMEOUT', 408)
        block = read(length - len(body))
        if time.monotonic() >= deadline:
            raise Rejected('ARCHIVE_READ_TIMEOUT', 408)
        if not block:
            raise Rejected('INCOMPLETE_ARCHIVE_REQUEST')
        body.extend(block)
    return bytes(body)


def archived_manifest(previous, digests):
    if not isinstance(previous, dict) or not previous.get('available'):
        raise Rejected('ARCHIVE_HISTORY_NOT_FOUND', 409)
    leaves(previous)
    active = [previous] + previous.get('alternatives', [])
    if any(item['sha256'] in digests for item in active):
        raise Rejected('ARCHIVE_ACTIVE_RELEASE_FORBIDDEN', 409)
    selected = [item for item in previous.get('history', []) if item['sha256'] in digests]
    if len(selected) != len(digests) or {item['sha256'] for item in selected} != set(digests):
        raise Rejected('ARCHIVE_HISTORY_NOT_FOUND', 409)
    result = dict(previous, history=[item for item in previous.get('history', []) if item['sha256'] not in digests])
    leaves(result)
    return result, selected


def write_new(path, body, mode=0o600):
    with path.open('xb') as output:
        output.write(body)
        output.flush()
        os.fsync(output.fileno())
    path.chmod(mode)


def archive_record(directory, verify_files=False):
    common.no_links(directory)
    if not re.fullmatch(r'archive-\d{8}T\d{6}Z-[a-f0-9]{16}', directory.name):
        raise Rejected('ARCHIVE_INVENTORY_INVALID', 409)
    expected = {'receipt.json', 'release.before.json', 'release.after.json', 'downloads'}
    names = set(directory.iterdir())
    complete = directory / 'complete.json'
    if {path.name for path in names} not in [expected, expected | {'complete.json'}]:
        raise Rejected('ARCHIVE_INVENTORY_INVALID', 409)
    raw, total = {}, 0
    for name in expected - {'downloads'} | ({'complete.json'} if complete in names else set()):
        path = directory / name
        size = regular(path).st_size
        if size > 65536:
            raise Rejected('ARCHIVE_INVENTORY_INVALID', 409)
        raw[name] = path.read_bytes()
        total += size
    receipt = parse_json(raw['receipt.json'])
    keys = {'owner', 'archiveId', 'createdAt', 'formatVersion', 'sha256', 'bytes', 'revisionBefore', 'revisionAfter'}
    if not isinstance(receipt, dict) or set(receipt) != keys or receipt['owner'] != 'zhigou-publisher-archive-v1' or receipt['archiveId'] != directory.name or not isinstance(receipt['createdAt'], str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z', receipt['createdAt']):
        raise Rejected('ARCHIVE_INVENTORY_INVALID', 409)
    digests = archive_selection({key: receipt[key] for key in ['formatVersion', 'sha256']})
    before, after = [parse_json(raw[name]) for name in ['release.before.json', 'release.after.json']]
    updated, selected = archived_manifest(before, digests)
    if updated != after or type(receipt['bytes']) is not int or receipt['bytes'] != sum(item['bytes'] for item in selected):
        raise Rejected('ARCHIVE_INVENTORY_INVALID', 409)
    for key, name in [('revisionBefore', 'release.before.json'), ('revisionAfter', 'release.after.json')]:
        if receipt[key] != '"' + hashlib.sha256(raw[name]).hexdigest() + '"':
            raise Rejected('ARCHIVE_INVENTORY_INVALID', 409)
    downloads = directory / 'downloads'
    common.no_links(downloads)
    files = {Path(item['url']).name + suffix for item in selected for suffix in ['', '.sha256']}
    if {path.name for path in downloads.iterdir()} != files:
        raise Rejected('ARCHIVE_INVENTORY_INVALID', 409)
    for item in selected:
        apk, checksum = [downloads / Path(item[key]).name for key in ['url', 'checksumUrl']]
        apk_size, checksum_size = regular(apk).st_size, regular(checksum).st_size
        if apk_size != item['bytes'] or checksum_size > 1024 or checksum.read_text(encoding='ascii') != item['sha256'] + '  ' + apk.name + '\n':
            raise Rejected('ARCHIVE_INVENTORY_INVALID', 409)
        if verify_files:
            common.verify_download(directory, item, None)
        total += apk_size + checksum_size
    if complete in names and parse_json(raw['complete.json']) != {'archiveId': directory.name, 'complete': True}:
        raise Rejected('ARCHIVE_INVENTORY_INVALID', 409)
    return dict(receipt, complete=complete in names), total


@contextmanager
def process_lock(root):
    path = root / '.publisher.lock'
    common.no_links(path)
    with path.open('a+b') as lock:
        regular(path)
        if os.name == 'posix':
            import fcntl
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        else:
            import msvcrt
            if not lock.tell():
                lock.write(b'0'); lock.flush()
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        yield


class Publisher:
    def __init__(self, root):
        self.root = Path(root).absolute()
        common.no_links(self.root)
        self.downloads = self.root / 'downloads'
        self.staging = self.root / 'staging'
        self.archives = self.root / 'archives'
        self.busy = threading.Lock()
        for directory in [self.downloads, self.staging, self.archives]:
            common.no_links(directory)
            directory.mkdir(parents=True, exist_ok=True, mode=0o755 if directory == self.downloads else 0o700)
        self.ensure_ready()
        manifest = self.downloads / 'release.json'
        if not manifest.exists():
            if any(self.downloads.iterdir()):
                raise Rejected('UNKNOWN_DOWNLOAD_FILES', 409)
            with manifest.open('xb') as target:
                target.write(encoded({'available': False}))
            manifest.chmod(0o644)
        read_manifest(self.downloads, True)

    def ensure_ready(self):
        for path in self.staging.iterdir():
            code = 'INTERRUPTED_ARCHIVE_REQUIRES_INSPECTION' if path.name.startswith('archive-') else 'INTERRUPTED_UPLOAD_REQUIRES_INSPECTION'
            raise Rejected(code, 409)
        records, _ = self.archive_inventory()
        if any(not record['complete'] for record in records):
            raise Rejected('INTERRUPTED_ARCHIVE_REQUIRES_INSPECTION', 409)

    def archive_inventory(self):
        records, total = [], 0
        for directory in self.archives.iterdir():
            if len(records) >= MAX_ARCHIVES:
                raise Rejected('ARCHIVE_CAPACITY_EXCEEDED', 409)
            record, size = archive_record(directory)
            records.append(record)
            total += size
            if total > MAX_ARCHIVE_BYTES:
                raise Rejected('ARCHIVE_CAPACITY_EXCEEDED', 409)
        return sorted(records, key=lambda item: item['archiveId']), total

    def archive(self, selection, expected_revision):
        digests = archive_selection(selection)
        self.ensure_ready()
        previous, revision = read_manifest(self.downloads, True)
        if revision != expected_revision:
            raise Rejected('REVISION_CONFLICT', 409)
        updated, selected = archived_manifest(previous, digests)
        records, used = self.archive_inventory()
        # Keep manifests/receipts and a small free-space margin within the admission budget.
        required = sum(item['bytes'] for item in selected) + 256 * 1024
        if len(records) >= MAX_ARCHIVES or used + required > MAX_ARCHIVE_BYTES:
            raise Rejected('ARCHIVE_CAPACITY_EXCEEDED', 409)
        if shutil.disk_usage(self.root).free < required + 16 * 1024 * 1024:
            raise Rejected('INSUFFICIENT_SPACE', 507)
        archive_id = 'archive-' + time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()) + '-' + secrets.token_hex(8)
        transaction = self.staging / archive_id
        transaction.mkdir(mode=0o700)
        sync_directory(self.staging)
        # Retain this marker on every partial failure; never guess whether a commit happened.
        payload = transaction / 'payload'
        payload.mkdir(mode=0o700)
        copies = payload / 'downloads'
        copies.mkdir(mode=0o700)
        for item in selected:
            for key in ['url', 'checksumUrl']:
                source = self.downloads / Path(item[key]).name
                regular(source)
                with source.open('rb') as incoming, (copies / source.name).open('xb') as output:
                    shutil.copyfileobj(incoming, output, 1024 * 1024)
                    output.flush()
                    os.fsync(output.fileno())
                (copies / source.name).chmod(0o600)
        before = (self.downloads / 'release.json').read_bytes()
        after = encoded(updated)
        receipt = dict(owner='zhigou-publisher-archive-v1', formatVersion=1, archiveId=archive_id,
                       createdAt=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), sha256=digests,
                       bytes=sum(item['bytes'] for item in selected), revisionBefore=revision,
                       revisionAfter='"' + hashlib.sha256(after).hexdigest() + '"')
        write_new(payload / 'receipt.json', encoded(receipt))
        write_new(payload / 'release.before.json', before)
        write_new(payload / 'release.after.json', after)
        write_new(transaction / 'release.next.json', after, 0o644)
        sync_directory(copies)
        sync_directory(payload)
        sync_directory(transaction)
        destination = self.archives / archive_id
        if destination.exists() or destination.is_symlink():
            raise Rejected('ARCHIVE_INVENTORY_INVALID', 409)
        os.rename(payload, destination)
        archive_record(destination, True)
        sync_directory(self.archives)
        sync_directory(transaction)
        os.replace(transaction / 'release.next.json', self.downloads / 'release.json')
        sync_directory(self.downloads)
        # The private, verified copy is durable before any public copy is removed.
        for item in selected:
            common.verify_download(self.root, item, None)
            for key in ['url', 'checksumUrl']:
                source = self.downloads / Path(item[key]).name
                regular(source)
                source.unlink()
        sync_directory(self.downloads)
        read_manifest(self.downloads, True)
        write_new(destination / 'complete.json', encoded({'archiveId': archive_id, 'complete': True}))
        sync_directory(destination)
        transaction.rmdir()
        sync_directory(self.staging)
        return updated, dict(receipt, complete=True)

    def upload(self, source, item, expected_revision, alternative=False):
        self.ensure_ready()
        previous, revision = read_manifest(self.downloads, True)
        if expected_revision != revision:
            raise Rejected('REVISION_CONFLICT', 409)
        manifest = next_manifest(previous, item, alternative)
        if shutil.disk_usage(self.root).free < item['bytes'] + 16 * 1024 * 1024:
            raise Rejected('INSUFFICIENT_SPACE', 507)
        created = []
        with tempfile.TemporaryDirectory(prefix='upload-', dir=self.staging) as temporary:
            frozen = Path(temporary) / 'upload.apk'
            checksum = hashlib.sha256()
            remaining = item['bytes']
            deadline = time.monotonic() + 600
            with frozen.open('xb') as output:
                while remaining:
                    if time.monotonic() >= deadline:
                        raise Rejected('UPLOAD_TIMEOUT', 408)
                    read = getattr(source, 'read1', source.read)
                    block = read(min(1024 * 1024, remaining))
                    if time.monotonic() >= deadline:
                        raise Rejected('UPLOAD_TIMEOUT', 408)
                    if not block:
                        raise Rejected('INCOMPLETE_UPLOAD')
                    output.write(block); checksum.update(block); remaining -= len(block)
                output.flush(); os.fsync(output.fileno())
            if checksum.hexdigest() != item['sha256']:
                raise Rejected('APK_DIGEST_MISMATCH')
            if apk_abis(frozen) != sorted(item['abis']):
                raise Rejected('APK_ABI_MISMATCH')
            try:
                target = self.downloads / Path(item['url']).name
                if not target.exists():
                    # The process lock and strict directory inventory exclude another writer.
                    frozen.chmod(0o644)
                    os.rename(frozen, target); created.append(target)
                    path = self.downloads / Path(item['checksumUrl']).name
                    with path.open('xb') as output:
                        created.append(path)
                        output.write((item['sha256'] + '  ' + target.name + '\n').encode('ascii'))
                        output.flush(); os.fsync(output.fileno())
                    path.chmod(0o644)
                temporary_manifest = Path(temporary) / 'release.json'
                with temporary_manifest.open('xb') as output:
                    created.append(temporary_manifest)
                    output.write(encoded(manifest)); output.flush(); os.fsync(output.fileno())
                temporary_manifest.chmod(0o644)
                sync_directory(self.downloads)
                os.replace(temporary_manifest, self.downloads / 'release.json')
            except BaseException:
                for path in reversed(created):
                    path.unlink(missing_ok=True)
                raise
            # After the commit point, errors must not delete referenced APKs.
            sync_directory(self.downloads)
        return manifest


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 4

    def __init__(self, port, publisher, token):
        self.publisher, self.token = publisher, token
        self.challenges = Challenges()
        self.slots = threading.BoundedSemaphore(4)
        super().__init__(('127.0.0.1', port), Handler)

    def process_request(self, request, address):
        if not self.slots.acquire(False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()

    def handle_error(self, request, address):
        print('PUBLISH_REQUEST_FAILED', flush=True)


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = 'ZhigouPublisher/3'
    sys_version = ''

    def setup(self):
        self.request.settimeout(15)
        self.signed_context = None
        super().setup()

    def log_message(self, *_):
        pass

    def reply(self, status, value):
        body = encoded(value)
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'close')
        if self.signed_context:
            self.send_header('X-Publish-Signature', response_signature(self.server.token, self.signed_context, status, body))
        self.end_headers()
        self.wfile.write(body)

    def header(self, name, default=''):
        values = self.headers.get_all(name, [])
        if len(values) > 1 or any(not value.isascii() or any(ord(char) < 32 or ord(char) == 127 for char in value) for value in values):
            raise Rejected('INVALID_HEADERS')
        return values[0] if values else default

    def authenticate(self):
        if self.path not in [CHALLENGE_PATH, RELEASE_PATH, ARCHIVE_PATH] or self.headers.get_all('Origin'):
            raise Rejected('INVALID_ENDPOINT', 404)
        if self.headers.get_all('Authorization'):
            raise Rejected('BEARER_DISABLED', 401)
        if sum(len(key) + len(value) for key, value in self.headers.items()) > 16384:
            raise Rejected('HEADERS_TOO_LARGE', 431)
        if self.headers.get_all('Transfer-Encoding'):
            raise Rejected('CONTENT_LENGTH_REQUIRED', 411)
        nonce, ticket, signature, body_sha = [self.header(name) for name in
            ['X-Publish-Nonce', 'X-Publish-Challenge', 'X-Publish-Signature', 'X-Publish-Body-Sha256']]
        if not re.fullmatch('[a-f0-9]{32}', nonce) or not re.fullmatch('[a-f0-9]{64}', signature) or not re.fullmatch('[a-f0-9]{64}', body_sha):
            raise Rejected('SIGNATURE_REQUIRED', 401)
        if ticket != '-' and not re.fullmatch('[a-f0-9]{64}', ticket):
            raise Rejected('CHALLENGE_INVALID', 401)
        content_type, length, metadata, revision = [self.header(name, '0' if name == 'Content-Length' else '') for name in
            ['Content-Type', 'Content-Length', 'X-Release-Metadata', 'If-Match']]
        if not re.fullmatch(r'0|[1-9][0-9]{0,9}', length) or len(metadata) > 8192:
            raise Rejected('INVALID_HEADERS')
        expected = request_signature(self.server.token, self.command, self.path, nonce, ticket, content_type, length, metadata, revision, body_sha)
        if not hmac.compare_digest(signature, expected):
            raise Rejected('SIGNATURE_INVALID', 401)
        self.signed_context = (self.command, self.path, nonce, signature)
        self.body_sha = body_sha
        if self.command == 'GET' and (length != '0' or content_type or metadata or revision or body_sha != EMPTY_SHA256):
            raise Rejected('INVALID_READ_REQUEST')
        if self.path == CHALLENGE_PATH:
            if self.command != 'GET' or ticket != '-':
                raise Rejected('INVALID_CHALLENGE_REQUEST')
        else:
            self.server.challenges.consume(nonce, ticket)

    def perform(self, upload):
        acquired = False
        try:
            self.authenticate()
            if self.path == CHALLENGE_PATH:
                self.reply(200, self.server.challenges.issue(self.signed_context[2]))
                return
            acquired = self.server.publisher.busy.acquire(False)
            if not acquired:
                raise Rejected('PUBLISH_BUSY', 409)
            if not upload:
                if self.path == ARCHIVE_PATH:
                    records, used = self.server.publisher.archive_inventory()
                    self.reply(200, {'archives': records, 'bytes': used, 'maxArchives': MAX_ARCHIVES, 'maxBytes': MAX_ARCHIVE_BYTES})
                    return
                manifest, revision = read_manifest(self.server.publisher.downloads)
                self.reply(200, {'release': manifest, 'revision': revision, 'capabilities': ['archive-history-v1']})
                return
            if self.headers.get('Transfer-Encoding') or len(self.headers.get_all('Content-Length', [])) != 1:
                raise Rejected('CONTENT_LENGTH_REQUIRED', 411)
            if self.path == ARCHIVE_PATH:
                if self.header('Content-Type') != 'application/json' or self.header('X-Release-Metadata'):
                    raise Rejected('ARCHIVE_CONTENT_TYPE_REQUIRED', 415)
                length = int(self.header('Content-Length'))
                if not 1 <= length <= 2048:
                    raise Rejected('ARCHIVE_SIZE_INVALID', 413)
                revision = self.header('If-Match')
                if not re.fullmatch(r'"[a-f0-9]{64}"', revision):
                    raise Rejected('REVISION_REQUIRED', 428)
                body = read_archive_body(self.rfile, length)
                if len(body) != length or hashlib.sha256(body).hexdigest() != self.body_sha:
                    raise Rejected('SIGNED_DIGEST_MISMATCH')
                manifest, receipt = self.server.publisher.archive(parse_json(body), revision)
                self.reply(200, {'ok': True, 'release': manifest, 'archive': receipt})
                return
            if self.headers.get('Content-Type') != 'application/vnd.android.package-archive':
                raise Rejected('APK_CONTENT_TYPE_REQUIRED', 415)
            values = self.headers.get_all('X-Release-Metadata', [])
            if len(values) != 1 or len(values[0]) > 8192:
                raise Rejected('INVALID_METADATA')
            try:
                metadata = parse_json(base64.b64decode(values[0], validate=True))
                if not isinstance(metadata, dict) or set(metadata) != {'release', 'alternative', 'certificateSha256'} or type(metadata['alternative']) is not bool or metadata['certificateSha256'] != CERTIFICATE:
                    raise Rejected('INVALID_METADATA')
                item = metadata['release']
                validate_leaf(item)
                if item['sha256'] != self.body_sha:
                    raise Rejected('SIGNED_DIGEST_MISMATCH')
                length = self.headers['Content-Length']
                if not re.fullmatch(r'[0-9]{1,10}', length) or int(length) != item['bytes'] or int(length) > MAX_BYTES:
                    raise Rejected('UPLOAD_SIZE_INVALID', 413)
            except (ValueError, TypeError, KeyError) as error:
                raise Rejected('INVALID_METADATA') from error
            revisions = self.headers.get_all('If-Match', [])
            if len(revisions) != 1 or not re.fullmatch(r'"[a-f0-9]{64}"', revisions[0]):
                raise Rejected('REVISION_REQUIRED', 428)
            manifest = self.server.publisher.upload(self.rfile, item, revisions[0], metadata['alternative'])
            self.reply(200, {'ok': True, 'release': manifest})
        except Rejected as error:
            self.reply(error.status, {'ok': False, 'code': error.code})
        except (TimeoutError, socket.timeout):
            self.reply(408, {'ok': False, 'code': 'UPLOAD_TIMEOUT'})
        except (OSError, ValueError):
            self.reply(500, {'ok': False, 'code': 'PUBLISH_IO_FAILED'})
        finally:
            if acquired:
                self.server.publisher.busy.release()
            self.close_connection = True

    def do_GET(self):
        self.perform(False)

    def do_POST(self):
        self.perform(True)


def initialize(root, source):
    source = Path(source).absolute()
    common.no_links(source)
    release = common.verify_release(source)
    root = Path(root).absolute()
    common.no_links(root)
    root.mkdir(parents=True, exist_ok=True)
    if (root / 'downloads').exists():
        raise Rejected('INITIALIZATION_WOULD_OVERWRITE', 409)
    with tempfile.TemporaryDirectory(prefix='initialize-', dir=root) as temporary:
        target = Path(temporary) / 'downloads'
        target.mkdir()
        for name in ['release.json'] + [Path(item['url']).name + suffix for item in leaves(release) for suffix in ['', '.sha256']]:
            original = source / 'downloads' / name
            regular(original)
            shutil.copyfile(original, target / name)
            (target / name).chmod(0o644)
        read_manifest(target, True)
        target.chmod(0o755)
        os.rename(target, root / 'downloads')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data-dir', type=Path, required=True)
    parser.add_argument('--token-file', type=Path)
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--initialize-from', type=Path)
    args = parser.parse_args()
    args.data_dir = args.data_dir.absolute()
    common.no_links(args.data_dir)
    args.data_dir.mkdir(parents=True, exist_ok=True)
    with process_lock(args.data_dir):
        if args.initialize_from:
            initialize(args.data_dir, args.initialize_from)
            print('PUBLISHER_INITIALIZED; existing static release retained', flush=True)
            return
        if not args.token_file or not 1024 <= args.port <= 65535:
            parser.error('A private token file and port 1024-65535 are required')
        if os.name == 'posix' and os.geteuid() == 0:
            parser.error('Run the service as its dedicated non-root user')
        info = regular(args.token_file)
        if info.st_size > 128 or (os.name == 'posix' and info.st_mode & 0o007):
            parser.error('Token file must be private, not world-readable')
        token = args.token_file.read_text(encoding='ascii').strip()
        if not re.fullmatch('[a-f0-9]{64}', token):
            parser.error('Token must contain 32 random bytes encoded as lowercase hex')
        with Server(args.port, Publisher(args.data_dir), token) as server:
            print('PUBLISHER_READY: signed protocol v1; loopback only', flush=True)
            server.serve_forever(poll_interval=0.25)


if __name__ == '__main__':
    try:
        main()
    except (Rejected, OSError, ValueError) as failure:
        raise SystemExit(failure.code if isinstance(failure, Rejected) else 'PUBLISHER_STARTUP_FAILED')
