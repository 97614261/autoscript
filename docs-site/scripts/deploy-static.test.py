import importlib.util
import io
import json
import hashlib
from email.message import Message
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

spec = importlib.util.spec_from_file_location('deploy_static', Path(__file__).with_name('deploy-static.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def archive_entries(extra=None):
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode='w') as archive:
        for name in ['index.html', 'download.html', '404.html', 'api/index.html', 'downloads/release.json']:
            entry = tarfile.TarInfo('./' + name)
            entry.size = 1
            archive.addfile(entry, io.BytesIO(b'x'))
        if extra:
            archive.addfile(extra)
    data.seek(0)
    return tarfile.open(fileobj=data, mode='r:')


class DeploymentSafetyTest(unittest.TestCase):
    def test_update_http_never_follows_redirects(self):
        self.assertIsNone(module.NoUpdateRedirect().redirect_request(None, None, 302, '', {}, 'https://other.example/'))

    def test_http_checks_all_current_alternatives_and_history(self):
        entries = [dict(sha256=str(index) * 64, url='/downloads/test-' + str(index) + '.apk', bytes=100 + index)
                   for index in range(3)]
        release = dict(entries[0], alternatives=[entries[1]], history=[entries[2]])
        seen = []

        class Response(io.BytesIO):
            def __init__(self, body=b'', status=200, **headers):
                super().__init__(body); self.status = status; self.headers = Message()
                for key, value in headers.items():
                    self.headers[key.replace('_', '-')] = value

        class Client:
            def open(self, request, timeout):
                path = request.full_url.removeprefix('http://127.0.0.1')
                seen.append((path, request.method))
                if path == '/zhigou-not-found-check-0134':
                    raise urllib.error.HTTPError(request.full_url, 404, 'not found', {}, None)
                if path == '/downloads/release.json':
                    return Response(json.dumps(release).encode(), Content_Type='application/json', Cache_Control='no-cache')
                if path == '/download' and request.method == 'GET':
                    return Response(' '.join(item['sha256'] for item in entries).encode())
                for item in entries:
                    if path == item['url']:
                        if request.method == 'HEAD':
                            return Response(Content_Length=str(item['bytes']), Content_Type='application/vnd.android.package-archive', Content_Disposition='attachment')
                        return Response(b'x' * 16, 206, Content_Range='bytes 0-15/' + str(item['bytes']))
                return Response()

        with patch.object(module.urllib.request, 'build_opener', return_value=Client()):
            module.verify_http('docs.example.com', release)
        for item in entries:
            self.assertIn((item['url'], 'HEAD'), seen)
            self.assertIn((item['url'], 'GET'), seen)

    def test_app_update_manifest_requires_json_and_revalidation(self):
        release = {'available': False}
        class Response(io.BytesIO):
            status = 200
            def __init__(self, content_type, cache):
                super().__init__(json.dumps(release).encode())
                self.headers = Message()
                self.headers['Content-Type'] = content_type
                self.headers['Cache-Control'] = cache
        for cache in ['no-cache', 'no-store', 'public, max-age=0']:
            module.verify_update_response(Response('application/json', cache), release)
        with self.assertRaisesRegex(ValueError, 'application/json'):
            module.verify_update_response(Response('text/html', 'no-cache'), release)
        for cache in ['', 'public, max-age=31536000, immutable', 'max-age=01', 'max-age=0x']:
            with self.assertRaisesRegex(ValueError, 'long-cached'):
                module.verify_update_response(Response('application/json', cache), release)

    def test_architecture_candidates_are_verified_and_not_treated_as_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / 'downloads').mkdir()
            entries = []
            for abi in ['arm64-v8a', 'x86_64']:
                data = ('public-candidate-fixture-' + abi).encode()
                sha = hashlib.sha256(data).hexdigest()
                url = '/downloads/zhigou-0.1.0-dev-' + sha[:12] + '.apk'
                apk = root / url.lstrip('/'); apk.write_bytes(data)
                (root / (url + '.sha256').lstrip('/')).write_text(sha + '  ' + apk.name + '\n', encoding='utf-8')
                entries.append(dict(owner='zhigou-docs-download-v1', available=True, applicationId='com.zhigou.kaifaban',
                                    variant='release', status='optimized-candidate-unverified',
                                    certificateSha256='8e48ea72d827f96ea57c6b7bf4d566bac7581fa23106324bf3296fa9e2024f6c',
                                    profile='full', versionName='0.1.0', versionCode=1, minSdk=24,
                                    abis=[abi], sha256=sha, bytes=len(data), url=url, checksumUrl=url + '.sha256'))
            manifest = dict(entries[0], alternatives=[entries[1]], history=[])
            path = root / 'downloads/release.json'
            page = ' '.join(item['url'] + item['sha256'] for item in entries)
            (root / 'download.html').write_text(page, encoding='utf-8')
            path.write_text(json.dumps(manifest), encoding='utf-8')
            self.assertEqual(len(module.releases_of(module.verify_release(root))), 2)
            for key, value, expected in [('abis', ['arm64-v8a'], 'distinct ABI'),
                                         ('versionCode', 2, 'distinct ABI'),
                                         ('status', 'accepted', 'candidate'),
                                         ('certificateSha256', '0' * 64, 'candidate'),
                                         ('alternatives', [], 'nested')]:
                bad = dict(entries[1]); bad[key] = value
                path.write_text(json.dumps(dict(entries[0], alternatives=[bad])), encoding='utf-8')
                with self.assertRaisesRegex(ValueError, expected):
                    module.verify_release(root)
            path.write_text(json.dumps(manifest), encoding='utf-8')
            (root / entries[1]['url'].lstrip('/')).write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                module.verify_release(root)

    def test_history_is_fully_verified_and_tampering_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / 'downloads').mkdir()
            entries = []
            for index in range(2):
                data = ('public-test-apk-data-' + str(index)).encode()
                sha = hashlib.sha256(data).hexdigest()
                url = '/downloads/zhigou-0.1.0-dev-' + sha[:12] + '.apk'
                apk = root / url.lstrip('/'); apk.write_bytes(data)
                (root / (url + '.sha256').lstrip('/')).write_text(sha + '  ' + apk.name + '\n', encoding='utf-8')
                entries.append(dict(owner='zhigou-docs-download-v1', available=True, applicationId='com.zhigou.kaifaban',
                                    variant='debug', profile='full', versionName='0.1.0', versionCode=1, minSdk=24,
                                    abis=['arm64-v8a'], sha256=sha, bytes=len(data), url=url, checksumUrl=url + '.sha256'))
            manifest = dict(entries[0], history=[entries[1]])
            (root / 'downloads/release.json').write_text(json.dumps(manifest), encoding='utf-8')
            (root / 'download.html').write_text(' '.join(item['url'] + item['sha256'] for item in entries), encoding='utf-8')
            self.assertEqual(len(module.verify_release(root)['history']), 1)
            (root / entries[1]['url'].lstrip('/')).write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                module.verify_release(root)

    def test_expected_archive_inventory(self):
        with archive_entries() as archive:
            self.assertEqual(len(module.inspect_archive(archive)), 5)

    def test_host_cannot_inject_nginx_or_headers(self):
        for host in ['; root /;', 'example.com\r\nBad: true', 'https://example.com/', '*', 'a b']:
            with self.assertRaises(ValueError):
                module.safe_host(host)
        self.assertEqual(module.safe_host('docs.example.com'), 'docs.example.com')

    def test_parent_and_absolute_paths_rejected(self):
        for name in ['../../etc/passwd', '/etc/passwd', '..\\etc\\passwd', '.env']:
            with archive_entries(tarfile.TarInfo(name)) as archive, self.assertRaises(ValueError):
                module.inspect_archive(archive)

    def test_archive_links_rejected(self):
        for kind in [tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.FIFOTYPE]:
            entry = tarfile.TarInfo('link'); entry.type = kind; entry.linkname = '/etc'
            with archive_entries(entry) as archive, self.assertRaises(ValueError):
                module.inspect_archive(archive)

    def test_duplicate_paths_rejected(self):
        with archive_entries(tarfile.TarInfo('index.html')) as archive, self.assertRaises(ValueError):
            module.inspect_archive(archive)

    def test_nginx_uses_clean_urls_and_real_not_found(self):
        config = module.nginx_config('docs.example.com')
        self.assertIn('server_name docs.example.com;', config)
        self.assertIn('try_files $uri $uri.html $uri/ =404;', config)
        self.assertIn('application/vnd.android.package-archive', config)
        self.assertIn('Content-Disposition "attachment"', config)
        self.assertNotIn('default_server', config)
        self.assertNotIn('try_files $uri /index.html', config)


if __name__ == '__main__':
    unittest.main()
