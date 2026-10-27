#!/usr/bin/env python3
"""Upgrade an existing signed publisher; preserve downloads, keys and configuration."""
import argparse
import ast
import hashlib
import hmac
import http.client
import json
import os
from pathlib import Path
import re
import runpy
import secrets
import stat
import subprocess
import sys
import tempfile
import time

INSTALL = Path('/opt/zhigou-publisher')
DATA = Path('/var/lib/zhigou-publisher')
TOKEN = Path('/etc/zhigou-publisher/token')
SOURCES = ('publish-server.py', 'deploy-static.py')
PACKAGE_FILES = {*SOURCES, 'update-publisher.py', 'UPDATE.md', 'nginx-publish.conf', 'nginx-downloads.conf', 'zhigou-publisher.service'}


def regular(path, limit=1024 * 1024):
    for part in [path, *path.parents]:
        if part.is_symlink():
            raise ValueError('Links are not allowed')
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > limit:
        raise ValueError('Invalid regular file')
    return path.read_bytes()


def verify_bundle(directory):
    manifest_raw = regular(directory / 'delivery.json')
    manifest = json.loads(manifest_raw)
    if manifest.get('owner') != 'zhigou-publisher-update-v1' or manifest.get('formatVersion') != 1 or manifest.get('protocol') != 'signed-http-v1' or manifest.get('features') != ['archive-history-v1']:
        raise ValueError('Wrong delivery identity')
    files = manifest['files']
    if not isinstance(files, list) or len(files) != len(PACKAGE_FILES) or {item['filename'] for item in files} != PACKAGE_FILES:
        raise ValueError('Wrong delivery inventory')
    expected = {'delivery.json': hashlib.sha256(manifest_raw).hexdigest()}
    updates = {}
    for item in files:
        raw = regular(directory / item['filename'])
        digest = hashlib.sha256(raw).hexdigest()
        if digest != item['sha256'] or len(raw) != item['bytes']:
            raise ValueError('Delivery digest mismatch')
        expected[item['filename']] = digest
        if item['filename'] in SOURCES:
            updates[item['filename']] = raw
        if item['filename'].endswith('.py'):
            ast.parse(raw.decode('utf-8'))
    lines = regular(directory / 'CHECKSUMS.sha256').decode('ascii').splitlines()
    checks = {}
    for line in lines:
        if not re.fullmatch(r'[a-f0-9]{64}  [A-Za-z0-9_.-]+', line):
            raise ValueError('Invalid checksum line')
        digest, name = line.split('  ')
        if name in checks:
            raise ValueError('Duplicate checksum')
        checks[name] = digest
    if checks != expected:
        raise ValueError('Checksum inventory mismatch')
    return updates


def command(*arguments):
    return subprocess.run(arguments, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=45, text=True).stdout.strip()


def install_bytes(path, body, mode):
    handle, temporary = tempfile.mkstemp(prefix='.publisher-update-', dir=path.parent)
    pending = Path(temporary)
    try:
        with os.fdopen(handle, 'wb') as output:
            output.write(body)
            output.flush()
            os.fsync(output.fileno())
        pending.chmod(mode)
        os.replace(pending, path)
        if os.name == 'posix':
            handle = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(handle)
            finally:
                os.close(handle)
    finally:
        if pending.exists():
            pending.unlink()


def signed_ready(library, token, expected_revision):
    def request(path, nonce, ticket):
        signature = library['request_signature'](token, 'GET', path, nonce, ticket)
        connection = http.client.HTTPConnection('127.0.0.1', 8765, timeout=2)
        try:
            connection.request('GET', path, headers={'X-Publish-Nonce': nonce, 'X-Publish-Challenge': ticket,
                'X-Publish-Signature': signature, 'X-Publish-Body-Sha256': library['EMPTY_SHA256']})
            response = connection.getresponse()
            body = response.read(65537)
            if len(body) > 65536 or response.status != 200 or response.getheader('Content-Type') != 'application/json':
                raise ValueError('Service is not ready')
            expected = library['response_signature'](token, ('GET', path, nonce, signature), response.status, body)
            if not hmac.compare_digest(response.getheader('X-Publish-Signature', ''), expected):
                raise ValueError('Invalid signed service response')
            return library['parse_json'](body)
        finally:
            connection.close()
    nonce = secrets.token_hex(16)
    ticket = request(library['CHALLENGE_PATH'], nonce, '-')
    if ticket.get('nonce') != nonce or ticket.get('protocol') != 1 or ticket.get('validForSeconds') != 120:
        raise ValueError('Invalid service challenge')
    state = request(library['RELEASE_PATH'], nonce, ticket['challenge'])
    if state.get('revision') != expected_revision or 'archive-history-v1' not in state.get('capabilities', []):
        raise ValueError('Service state does not match')


def require_administrator():
    if sys.version_info < (3, 9) or os.name != 'posix' or os.geteuid() != 0:
        raise ValueError('Run with sudo and Python 3.9+ on the existing Linux server')


def upgrade(directory):
    updates = verify_bundle(directory)
    require_administrator()
    for path in [INSTALL, DATA]:
        if not path.is_dir() or path.is_symlink():
            raise ValueError('Missing existing installation')
    originals = {name: (regular(INSTALL / name), stat.S_IMODE((INSTALL / name).stat().st_mode)) for name in SOURCES}
    regular(DATA / 'downloads' / 'release.json', 65536)
    if command('systemctl', 'show', 'zhigou-publisher', '--property=User', '--value') != 'zhigou-publisher':
        raise ValueError('Unexpected service user')
    command('systemctl', 'is-active', '--quiet', 'zhigou-publisher')
    command('runuser', '-u', 'zhigou-publisher', '--', sys.executable, '-B', '-c',
            'import os; assert os.access("/etc/zhigou-publisher/token", os.R_OK)')
    # A new root-private backup is never overwritten or automatically removed.
    backup = Path(tempfile.mkdtemp(prefix='before-archive-', dir=INSTALL))
    for name, (body, mode) in originals.items():
        install_bytes(backup / name, body, mode)
    print('PUBLISH_BACKUP=' + str(backup), flush=True)
    stopped = False
    try:
        command('systemctl', 'stop', 'zhigou-publisher')
        stopped = True
        manifest = regular(DATA / 'downloads' / 'release.json', 65536)
        revision = '"' + hashlib.sha256(manifest).hexdigest() + '"'
        for name in SOURCES:
            install_bytes(INSTALL / name, updates[name], 0o644)
        command('runuser', '-u', 'zhigou-publisher', '--', sys.executable, '-B', '-c',
                'import runpy\nfrom pathlib import Path\np=runpy.run_path("/opt/zhigou-publisher/publish-server.py")\n'
                'root=Path("/var/lib/zhigou-publisher")\nwith p["process_lock"](root):\n p["Publisher"](root)\n')
        library = runpy.run_path(str(INSTALL / 'publish-server.py'))
        token = regular(TOKEN, 128).decode('ascii').strip()
        if not re.fullmatch('[a-f0-9]{64}', token):
            raise ValueError('Invalid existing token')
        command('systemctl', 'start', 'zhigou-publisher')
        deadline = time.monotonic() + 25
        while True:
            try:
                signed_ready(library, token, revision)
                break
            except (OSError, ValueError, http.client.HTTPException):
                if time.monotonic() >= deadline:
                    raise ValueError('New publisher did not become ready')
                time.sleep(0.25)
        command('systemctl', 'is-active', '--quiet', 'zhigou-publisher')
        print('PUBLISHER_UPDATE_OK: signed archive capability verified; downloads/key/Nginx/systemd unchanged')
    except BaseException:
        if stopped:
            try:
                command('systemctl', 'stop', 'zhigou-publisher')
                for name, (body, mode) in originals.items():
                    install_bytes(INSTALL / name, body, mode)
                command('systemctl', 'start', 'zhigou-publisher')
                command('systemctl', 'is-active', '--quiet', 'zhigou-publisher')
                print('UPDATE_ROLLED_BACK: previous code restored; backup retained', flush=True)
            except BaseException:
                print('UPDATE_REQUIRES_INSPECTION: backup retained; check service status', flush=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-bundle', action='store_true', help='Validate delivery only; no server changes')
    args = parser.parse_args()
    directory = Path(__file__).absolute().parent
    if args.check_bundle:
        verify_bundle(directory)
        print('PUBLISHER_BUNDLE_OK: no server changes')
    else:
        upgrade(directory)


if __name__ == '__main__':
    try:
        main()
    except (Exception, KeyboardInterrupt):
        raise SystemExit('PUBLISHER_UPDATE_FAILED: no data or credentials were reset; inspect the retained backup/status')
