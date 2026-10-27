#!/usr/bin/env python3
"""Bootstrap a fresh, isolated Nginx site from a verified documentation archive.

Run on the destination Linux host, not on the development machine. Never stores
SSH credentials, changes firewall rules, removes old sites, or upgrades the OS.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import time
import urllib.error
import urllib.request

BASE = Path('/var/www/zhigou-docs')
CONFIG = Path('/etc/nginx/sites-available/zhigou-docs.conf')
ENABLED = Path('/etc/nginx/sites-enabled/zhigou-docs.conf')
MAX_TOTAL = 512 * 1024 * 1024


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def safe_host(host):
    if not isinstance(host, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]{0,252}', host):
        raise ValueError('Host must be one IP address or ASCII hostname, not a URL')
    return host


def no_links(path):
    for item in (path,) + tuple(path.parents):
        if item.is_symlink():
            raise ValueError('Refusing linked deployment path: ' + str(item))


def inspect_archive(archive):
    members, names, total = [], set(), 0
    for member in archive:
        path = PurePosixPath(member.name)
        if path.is_absolute() or '..' in path.parts or '\\' in member.name:
            raise ValueError('Archive path escapes release directory')
        if not member.isdir() and not member.isfile():
            raise ValueError('Archive links and special entries are not allowed')
        if str(path) == '.':
            if not member.isdir():
                raise ValueError('Invalid archive root')
            continue
        if any(part.startswith('.') for part in path.parts):
            raise ValueError('Hidden files are not public deployment content')
        if str(path) in names:
            raise ValueError('Duplicate archive path')
        names.add(str(path))
        total += member.size
        if len(names) > 4096 or total > MAX_TOTAL or member.size < 0:
            raise ValueError('Archive exceeds deployment budget')
        members.append((member, path))
    if not {'index.html', 'download.html', '404.html', 'api/index.html', 'downloads/release.json'} <= names:
        raise ValueError('Missing required documentation pages')
    return members


def nginx_config(host):
    safe_host(host)
    return '''# Owned by zhigou documentation deploy-static.py
server {
    listen 80;
    listen [::]:80;
    server_name @HOST@;
    root /var/www/zhigou-docs/current;
    index index.html;
    server_tokens off;
    autoindex off;
    error_page 404 /404.html;
    location ~ /\\. { deny all; }
    location ~ \\.apk$ {
        types { }
        default_type application/vnd.android.package-archive;
        try_files $uri =404;
        add_header Content-Disposition "attachment";
        add_header Cache-Control "public, max-age=31536000, immutable";
        add_header X-Content-Type-Options "nosniff" always;
    }
    location /assets/ {
        try_files $uri =404;
        add_header Cache-Control "public, max-age=31536000, immutable";
        add_header X-Content-Type-Options "nosniff" always;
    }
    location / {
        try_files $uri $uri.html $uri/ =404;
        add_header Cache-Control "no-cache";
        add_header X-Content-Type-Options "nosniff" always;
    }
}
'''.replace('@HOST@', host)


def run(arguments, timeout=60, env=None, capture=False):
    return subprocess.run(arguments, check=True, timeout=timeout, env=env,
                          stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.STDOUT if capture else None, text=True)


def releases_of(release):
    if not isinstance(release, dict):
        raise ValueError('Invalid download manifest')
    history, alternatives = release.get('history', []), release.get('alternatives', [])
    if not isinstance(history, list) or not isinstance(alternatives, list) or len(history) + len(alternatives) >= 8:
        raise ValueError('Invalid release history budget')
    current = {key: value for key, value in release.items() if key not in ['history', 'alternatives']}
    return [current] + alternatives + history


def verify_release(root):
    path = root / 'downloads/release.json'
    if path.stat().st_size > 65536:
        raise ValueError('Download manifest too large')
    release = json.loads(path.read_text(encoding='utf-8'))
    releases = releases_of(release)
    urls, total = set(), 0
    page = (root / 'download.html').read_text(encoding='utf-8')
    for item in releases:
        verify_download(root, item, page)
        if item['url'] in urls:
            raise ValueError('Duplicate release history')
        urls.add(item['url'])
        total += item['bytes']
    if total > 448 * 1024 * 1024:
        raise ValueError('Download history exceeds byte budget')
    abis = set(release['abis'])
    for item in release.get('alternatives', []):
        if any(item.get(key) != release.get(key) for key in ['versionName', 'versionCode', 'variant', 'profile', 'minSdk', 'status', 'certificateSha256']) or abis.intersection(item['abis']):
            raise ValueError('Alternative must be a matching build for a distinct ABI')
        abis.update(item['abis'])
    allowed = {'release.json'} | {PurePosixPath(url).name + suffix for url in urls for suffix in ['', '.sha256']}
    if {item.name for item in path.parent.iterdir()} != allowed:
        raise ValueError('Unknown download files')
    return release


def verify_download(root, release, page):
    validate_download(release)
    apk = root / release['url'].lstrip('/')
    if apk.stat().st_size != release['bytes'] or digest(apk) != release['sha256']:
        raise ValueError('APK size or SHA-256 mismatch')
    checksum = root / (release['url'] + '.sha256').lstrip('/')
    if checksum.stat().st_size > 1024 or checksum.read_text(encoding='utf-8') != release['sha256'] + '  ' + apk.name + '\n':
        raise ValueError('APK checksum file mismatch')
    if page is not None and (release['url'] not in page or release['sha256'] not in page):
        raise ValueError('Download page and APK disagree')


def validate_download(release):
    if not isinstance(release, dict) or 'history' in release or 'alternatives' in release:
        raise ValueError('Invalid or nested release history')
    if release.get('owner') != 'zhigou-docs-download-v1' or release.get('applicationId') != 'com.zhigou.kaifaban' or release.get('available') is not True or release.get('variant') not in ['debug', 'release'] or release.get('profile') != 'full':
        raise ValueError('Not a Studio documentation download')
    version = release.get('versionName', '')
    if not isinstance(version, str) or not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z.+-]{0,39}', version) or type(release.get('versionCode')) is not int or not 1 <= release['versionCode'] <= 2147483647 or release.get('minSdk') != 24:
        raise ValueError('Invalid download version')
    abis = release.get('abis')
    if not isinstance(abis, list) or not abis or any(abi not in ['arm64-v8a', 'x86_64'] for abi in abis) or len(set(abis)) != len(abis):
        raise ValueError('Invalid download ABI')
    if release['variant'] == 'release' and (release.get('status') != 'optimized-candidate-unverified' or release.get('certificateSha256') != '8e48ea72d827f96ea57c6b7bf4d566bac7581fa23106324bf3296fa9e2024f6c' or len(abis) != 1):
        raise ValueError('Invalid optimized candidate identity')
    if release['variant'] == 'debug' and ('status' in release or 'certificateSha256' in release):
        raise ValueError('Invalid debug download status')
    description = release.get('description')
    if description is not None and (not isinstance(description, str) or not description.strip() or len(description) > 240 or any(ord(char) < 32 for char in description)):
        raise ValueError('Invalid release description')
    sha = release.get('sha256', '')
    url = release.get('url', '')
    if not isinstance(sha, str) or not re.fullmatch(r'[a-f0-9]{64}', sha) or url != '/downloads/zhigou-' + version + '-dev-' + sha[:12] + '.apk' or release.get('checksumUrl') != url + '.sha256':
        raise ValueError('Invalid APK download identity')
    if type(release.get('bytes')) is not int or not 22 <= release['bytes'] <= 1024 * 1024 * 1024:
        raise ValueError('Invalid APK size')


def verify_update_response(response, release):
    body = response.read(65537)
    if response.status != 200 or len(body) > 65536 or json.loads(body) != release:
        raise ValueError('HTTP manifest does not match the selected release')
    if response.headers.get_content_type() != 'application/json':
        raise ValueError('App update manifest must use application/json')
    cache = response.headers.get('Cache-Control', '').lower()
    if not re.search(r'(?:^|,)\s*(?:no-cache|no-store|max-age\s*=\s*0)\s*(?:,|$)', cache):
        raise ValueError('App update manifest must be revalidated, not long-cached')


class NoUpdateRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def verify_http(host, release, live_downloads=False):
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoUpdateRedirect())
    def request(path, method='GET', extra=None):
        headers = {'Host': host}
        headers.update(extra or {})
        return client.open(urllib.request.Request('http://127.0.0.1' + path, headers=headers, method=method), timeout=15)
    for path in ['/', '/download', '/api/', '/guide/quick-start']:
        with request(path, 'HEAD') as response:
            if response.status != 200:
                raise ValueError('Site page verification failed')
    with request('/downloads/release.json') as response:
        verify_update_response(response, release)
    with request('/download') as response:
        page = response.read(2 * 1024 * 1024 + 1)
        matches = b'data-live-downloads="v1"' in page if live_downloads else all(item['sha256'].encode() in page for item in releases_of(release))
        if len(page) > 2 * 1024 * 1024 or not matches:
            raise ValueError('HTTP download page does not match the selected release')
    for item in releases_of(release):
      with request(item['url'], 'HEAD') as response:
        if response.status != 200 or int(response.headers.get('Content-Length', '-1')) != item['bytes']:
            raise ValueError('HTTP APK length mismatch')
        if response.headers.get_content_type() != 'application/vnd.android.package-archive' or 'attachment' not in response.headers.get('Content-Disposition', ''):
            raise ValueError('APK download headers invalid')
      with request(item['url'], extra={'Range': 'bytes=0-15'}) as response:
        if response.status != 206 or response.headers.get('Content-Range') != 'bytes 0-15/' + str(item['bytes']) or len(response.read(17)) != 16:
            raise ValueError('APK range verification failed')
    try:
        request('/zhigou-not-found-check-0134')
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
    else:
        raise ValueError('Unknown URL did not return 404')


def deploy(options):
    if os.name != 'posix' or os.geteuid() != 0:
        raise ValueError('Run this script with sudo python3 on the Linux server')
    os.umask(0o022)
    safe_host(options.host)
    if not re.fullmatch(r'[a-f0-9]{64}', options.sha256):
        raise ValueError('A verified archive SHA-256 is required')
    archive_path = Path(options.archive).resolve(strict=True)
    if archive_path.stat().st_size > MAX_TOTAL or digest(archive_path) != options.sha256:
        raise ValueError('Archive SHA-256 mismatch; no service configuration changed')
    no_links(BASE)
    for target in [CONFIG, ENABLED, BASE / 'current']:
        if target.exists() or target.is_symlink():
            raise ValueError('Existing site preserved; inspect before updating: ' + str(target))
    release_id = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + options.sha256[:12]
    release_root = BASE / 'releases' / release_id
    no_links(release_root)
    # Validate the entire archive before creating or extracting a release.
    with tarfile.open(str(archive_path), 'r:gz') as archive:
        members = inspect_archive(archive)
        release_root.mkdir(parents=True, mode=0o755, exist_ok=False)
        for member, relative in members:
            destination = release_root.joinpath(*relative.parts)
            if member.isdir():
                destination.mkdir(parents=True, mode=0o755, exist_ok=True)
            else:
                destination.parent.mkdir(parents=True, mode=0o755, exist_ok=True)
                with archive.extractfile(member) as source, destination.open('xb') as output:
                    shutil.copyfileobj(source, output, 1024 * 1024)
                destination.chmod(0o644)
    release = verify_release(release_root)
    print('Archive and APK verified; release prepared.', flush=True)
    if not shutil.which('nginx'):
        if not options.install_nginx:
            raise ValueError('Nginx missing; explicit --install-nginx is required')
        environment = dict(os.environ, DEBIAN_FRONTEND='noninteractive', NEEDRESTART_MODE='l')
        run(['apt-get', 'update'], timeout=300, env=environment)
        run(['apt-get', 'install', '-y', '--no-install-recommends', 'nginx'], timeout=300, env=environment)
    existing = run(['nginx', '-T'], capture=True).stdout
    for values in re.findall(r'\bserver_name\s+([^;]+);', existing):
        if options.host.lower() in values.lower().split():
            raise ValueError('Host already belongs to an existing Nginx site; not overwritten')
    no_links(CONFIG.parent)
    no_links(ENABLED.parent)
    created = []
    try:
        with CONFIG.open('x', encoding='utf-8') as output:
            output.write(nginx_config(options.host))
        created.append(CONFIG)
        CONFIG.chmod(0o644)
        (BASE / 'current').symlink_to(release_root, target_is_directory=True)
        created.append(BASE / 'current')
        ENABLED.symlink_to(CONFIG)
        created.append(ENABLED)
        run(['nginx', '-t'])
        active = subprocess.run(['systemctl', 'is-active', '--quiet', 'nginx'], timeout=15).returncode == 0
        run(['systemctl', 'reload' if active else 'start', 'nginx'])
        # Reload is asynchronous: allow the new workers a bounded transition.
        for attempt in range(5):
            try:
                verify_http(options.host, release)
                break
            except (ValueError, OSError):
                if attempt == 4:
                    raise
                time.sleep(0.2)
    except Exception:
        # Keep all files recoverable; no recursive cleanup or unknown-file deletion.
        failed = BASE / ('failed-' + release_id)
        failed.mkdir(mode=0o700, exist_ok=False)
        for index, path in enumerate(reversed(created)):
            path.rename(failed / (str(index) + '-' + path.name))
        if shutil.which('nginx'):
            try:
                run(['nginx', '-t'])
                run(['systemctl', 'reload', 'nginx'])
            except Exception:
                print('Rollback retained; inspect Nginx service before retrying.', flush=True)
        raise
    print('DEPLOY_OK: http://' + options.host + '/download', flush=True)
    print('HTTP pages, APK MIME/size/range and 404 verified locally.', flush=True)
    print('External access, domain and HTTPS are separate checks; no firewall/SSH settings changed.', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--host', required=True)
    parser.add_argument('--install-nginx', action='store_true')
    try:
        deploy(parser.parse_args())
    except Exception as error:
        print('DEPLOY_FAILED: ' + str(error), flush=True)
        raise SystemExit(1)
