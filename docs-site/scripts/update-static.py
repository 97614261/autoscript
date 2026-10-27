#!/usr/bin/env python3
"""Check, update or roll back the existing isolated documentation site.

No SSH, package installation, configuration rewriting or release deletion.
Requires deploy-static.py next to this file. Run with sudo on the Linux server.
"""
import argparse
from contextlib import contextmanager, ExitStack
import datetime
import importlib.util
import os
from pathlib import Path
import re
import shutil
import stat
import tarfile
import time

spec = importlib.util.spec_from_file_location('zhigou_deploy', Path(__file__).with_name('deploy-static.py'))
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)
BASE = common.BASE
RELEASE_ID = re.compile(r'[0-9]{8}T[0-9]{6}Z-[a-f0-9]{12}')
EXTENSIONS = {'.html', '.css', '.js', '.json', '.svg', '.png', '.jpg', '.webp', '.ico', '.woff', '.woff2', '.ttf', '.apk', '.sha256', '.txt'}
LIVE_DOWNLOADS = None


def release_path(base, release_id):
    if not isinstance(release_id, str) or not RELEASE_ID.fullmatch(release_id):
        raise ValueError('Use one direct retained release ID, not a path')
    root = base / 'releases' / release_id
    common.no_links(root)
    return root


def current_release(base):
    common.no_links(base / 'releases')
    current = base / 'current'
    if not current.is_symlink():
        raise ValueError('Existing current symlink required; use bootstrap only for a new site')
    root = current.resolve(strict=True)
    expected = release_path(base, root.name)
    if root != expected or not root.is_dir():
        raise ValueError('Current must point to one direct owned release directory')
    return root


def verify_tree(root):
    common.no_links(root)
    count, total = 0, 0
    for directory, directories, files in os.walk(root, followlinks=False):
        for name in directories + files:
            path = Path(directory) / name
            info = path.lstat()
            count += 1
            if name.startswith('.') or path.is_symlink() or (not path.is_dir() and not path.is_file()):
                raise ValueError('Release contains links, hidden or special files')
            if path.is_file():
                if path.suffix not in EXTENSIONS or info.st_nlink != 1:
                    raise ValueError('Unexpected release file')
                total += info.st_size
            if count > 4096 or total > common.MAX_TOTAL:
                raise ValueError('Release exceeds deployment budget')
    for name in ['index.html', 'download.html', '404.html', 'api/index.html', 'guide/quick-start.html']:
        if not (root / name).is_file():
            raise ValueError('Missing required release page')
    return common.verify_release(root)


@contextmanager
def deployment_lock(base):
    import fcntl
    path = base / '.deploy.lock'
    common.no_links(path)
    fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
    try:
        if not stat.S_ISREG(os.fstat(fd).st_mode) or os.fstat(fd).st_nlink != 1:
            raise ValueError('Deployment lock must be one regular owned file')
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise ValueError('Another deployment is active; do not delete its lock') from error
        yield
    finally:
        os.close(fd)


def atomic_switch(base, expected, target):
    if current_release(base) != expected:
        raise ValueError('Current release changed; no switch performed')
    temporary = base / 'current.next'
    if temporary.exists() or temporary.is_symlink():
        raise ValueError('Existing current.next preserved; inspect before retrying')
    temporary.symlink_to(target, target_is_directory=True)
    try:
        os.replace(temporary, base / 'current')
    except Exception:
        # Only this invocation's temporary link is removed; releases are retained.
        if temporary.is_symlink() and os.readlink(temporary) == str(target):
            temporary.unlink()
        raise


def verify_live(host, release):
    for attempt in range(5):
        try:
            if LIVE_DOWNLOADS is None:
                common.verify_http(host, release)
            else:
                common.verify_http(host, LIVE_DOWNLOADS, live_downloads=True)
            return
        except (ValueError, OSError):
            if attempt == 4:
                raise
            time.sleep(0.2)


def activate(base, host, old_root, old_release, target, release):
    atomic_switch(base, old_root, target)
    try:
        verify_live(host, release)
    except Exception as error:
        try:
            atomic_switch(base, target, old_root)
            verify_live(host, old_release)
        except Exception as rollback_error:
            raise ValueError('ACTIVATION_AND_RESTORE_FAILED; all releases retained; inspect current and Nginx') from rollback_error
        raise ValueError('ACTIVATION_FAILED_RESTORED; previous release verified; candidate retained') from error


def prepare_archive(base, archive_path, expected_sha):
    if not isinstance(expected_sha, str) or not re.fullmatch(r'[a-f0-9]{64}', expected_sha):
        raise ValueError('Explicit verified archive SHA-256 required')
    common.no_links(archive_path)
    if not archive_path.is_file() or archive_path.stat().st_size > common.MAX_TOTAL or common.digest(archive_path) != expected_sha:
        raise ValueError('Archive SHA-256 mismatch')
    release_id = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + expected_sha[:12]
    target = release_path(base, release_id)
    if target.exists():
        raise ValueError('Candidate already exists; inspect or explicitly roll back to it')
    with tarfile.open(str(archive_path), 'r:gz') as archive:
        members = common.inspect_archive(archive)
        if shutil.disk_usage(base).free < sum(member.size for member, _ in members) + 64 * 1024 * 1024:
            raise ValueError('Insufficient free space; no release was created')
        target.mkdir(mode=0o755, exist_ok=False)
        for member, relative in members:
            destination = target.joinpath(*relative.parts)
            if member.isdir():
                destination.mkdir(parents=True, mode=0o755, exist_ok=True)
            else:
                destination.parent.mkdir(parents=True, mode=0o755, exist_ok=True)
                with archive.extractfile(member) as source, destination.open('xb') as output:
                    shutil.copyfileobj(source, output, 1024 * 1024)
                destination.chmod(0o644)
    return target, verify_tree(target)


def update(options):
    if os.name != 'posix' or os.geteuid() != 0:
        raise ValueError('Run with sudo python3 on the Linux server')
    os.umask(0o022)
    common.safe_host(options.host)
    common.no_links(BASE)
    if not BASE.is_dir():
        raise ValueError('Existing site required; update never bootstraps or installs Nginx')
    def preflight():
        old_root = current_release(BASE)
        old_release = verify_tree(old_root)
        if LIVE_DOWNLOADS is not None:
            old_release = LIVE_DOWNLOADS
        common.run(['nginx', '-t'])
        common.run(['systemctl', 'is-active', '--quiet', 'nginx'], timeout=15)
        verify_live(options.host, old_release)
        return old_root, old_release
    if options.check:
        root, release = preflight()
        print('CHECK_OK current=' + root.name + ' apk=' + release['sha256'])
        return
    if not options.expected_current or not re.fullmatch(r'[a-f0-9]{64}', options.expected_current):
        raise ValueError('Explicit --expected-current APK digest required')
    if not options.expected_release or not RELEASE_ID.fullmatch(options.expected_release):
        raise ValueError('Explicit --expected-release site release ID required')
    with deployment_lock(BASE):
        old_root, old_release = preflight()
        if old_release['sha256'] != options.expected_current:
            raise ValueError('Current APK differs from --expected-current; site preserved')
        if old_root.name != options.expected_release:
            raise ValueError('Current site differs from --expected-release; site preserved')
        if (BASE / 'current.next').exists() or (BASE / 'current.next').is_symlink():
            raise ValueError('Existing current.next preserved; inspect before retrying')
        if options.rollback:
            target = release_path(BASE, options.rollback)
            if target == old_root:
                raise ValueError('Selected release is already current')
            release = verify_tree(target)
        else:
            target, release = prepare_archive(BASE, Path(options.archive).absolute(), options.sha256)
        activate(BASE, options.host, old_root, old_release, target, release)
        print(('ROLLBACK_OK' if options.rollback else 'UPDATE_OK') + ' current=' + target.name + ' apk=' + release['sha256'])
        print('PREVIOUS_RELEASE=' + old_root.name)
        print('Local HTTP manifest, pages, APK size/MIME/range and 404 verified. Old releases retained.')
        print('External access/HTTPS are separate checks; no service reload or configuration changes.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--check', action='store_true')
    group.add_argument('--archive')
    group.add_argument('--rollback', metavar='RELEASE_ID')
    parser.add_argument('--sha256')
    parser.add_argument('--expected-current')
    parser.add_argument('--expected-release')
    parser.add_argument('--host', required=True)
    parser.add_argument('--downloads-root', type=Path, help='Explicit persistent downloads directory; stop the publisher service first')
    options = parser.parse_args()
    if options.archive and not options.sha256:
        parser.error('--archive requires --sha256')
    try:
        with ExitStack() as locks:
            if options.downloads_root:
                directory = options.downloads_root.absolute()
                common.no_links(directory)
                if directory.name != 'downloads':
                    raise ValueError('Persistent downloads directory must be named downloads')
                publisher_spec = importlib.util.spec_from_file_location('zhigou_publisher', Path(__file__).with_name('publish-server.py'))
                publisher = importlib.util.module_from_spec(publisher_spec)
                publisher_spec.loader.exec_module(publisher)
                locks.enter_context(publisher.process_lock(directory.parent))
                LIVE_DOWNLOADS, _ = publisher.read_manifest(directory, True)
                if not LIVE_DOWNLOADS.get('available'):
                    raise ValueError('Publish the initial APK before using persistent downloads')
            update(options)
    except Exception as error:
        print('UPDATE_FAILED: ' + str(error), flush=True)
        raise SystemExit(1)
