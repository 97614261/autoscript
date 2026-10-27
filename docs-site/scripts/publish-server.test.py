import base64
import hashlib
import http.client
import importlib.util
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-server.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def artifact(version=1, abi='arm64-v8a', full=True):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as apk:
        apk.writestr('AndroidManifest.xml', 'public synthetic fixture ' + str(version))
        apk.writestr('lib/' + abi + '/libengine_jni.so', b'')
        if full:
            for name in ['libopencv_java4.so', 'libonnxruntime.so', 'libonnxruntime4j_jni.so']:
                apk.writestr('lib/' + abi + '/' + name, b'')
            for name in ['en_PP-OCRv4_rec_mobile.onnx', 'en_PP-OCRv5_rec_mobile.onnx']:
                apk.writestr('assets/vision/' + name, b'')
    body = output.getvalue()
    digest = hashlib.sha256(body).hexdigest()
    url = '/downloads/zhigou-0.1.' + str(version) + '-dev-' + digest[:12] + '.apk'
    return body, dict(owner='zhigou-docs-download-v1', available=True, applicationId='com.zhigou.kaifaban',
                     variant='debug', profile='full', versionName='0.1.' + str(version), versionCode=version,
                     minSdk=24, abis=[abi], bytes=len(body), sha256=digest, builtAt='2026-10-21T12:00:00.000Z',
                     url=url, checksumUrl=url + '.sha256', description='Public synthetic fixture, not signed or executable')


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.publisher = module.Publisher(self.root)

    def tearDown(self):
        self.temporary.cleanup()

    def current(self):
        return module.read_manifest(self.publisher.downloads, True)

    def publish(self, body, release, alternative=False, revision=None):
        return self.publisher.upload(io.BytesIO(body), release, revision or self.current()[1], alternative)

    def test_publish_duplicate_and_history(self):
        body, release = artifact()
        first = self.publish(body, release)
        self.assertEqual(self.publish(body, release), first)
        second_body, second = artifact(2)
        result = self.publish(second_body, second)
        self.assertEqual(result['history'], [release])
        self.assertEqual(self.current()[0], result)
        self.assertEqual(list(self.publisher.staging.iterdir()), [])

    def test_distinct_architecture_alternative_and_replacement(self):
        body, primary = artifact()
        self.publish(body, primary)
        other, alternate = artifact(1, 'x86_64')
        result = self.publish(other, alternate, True)
        self.assertEqual(result['alternatives'], [alternate])
        self.assertEqual(self.publish(body, primary), result)
        with self.assertRaises(module.Rejected):
            self.publish(*artifact(2, 'x86_64'), alternative=True)
        with self.assertRaises(module.Rejected):
            self.publish(body, primary, True)

    def test_bad_hash_partial_zip_abi_and_profile_leave_current_untouched(self):
        before = self.current()
        body, release = artifact()
        for wrong_body, wrong_release in [(body[:-1], release), (b'x' * len(body), release),
                                          (body, dict(release, abis=['x86_64'])), artifact(full=False)]:
            with self.assertRaises(module.Rejected):
                self.publish(wrong_body, wrong_release)
            self.assertEqual(self.current(), before)
            self.assertEqual(list(self.publisher.staging.iterdir()), [])

    def test_revision_conflict_before_body_read(self):
        body, release = artifact()
        reader = io.BytesIO(body)
        with self.assertRaisesRegex(module.Rejected, ''):
            self.publisher.upload(reader, release, '"' + 'f' * 64 + '"')
        self.assertEqual(reader.tell(), 0)

    def test_final_read_crossing_deadline_cannot_publish(self):
        body, release = artifact()
        before = self.current()
        with patch.object(module.time, 'monotonic', side_effect=[0, 1, 601]):
            with self.assertRaises(module.Rejected) as failure:
                self.publish(body, release)
        self.assertEqual(failure.exception.code, 'UPLOAD_TIMEOUT')
        self.assertEqual(self.current(), before)
        self.assertEqual(list(self.publisher.staging.iterdir()), [])

    def test_downgrade_and_manifest_identity_rejected(self):
        self.publish(*artifact(2))
        before = self.current()
        with self.assertRaises(module.Rejected):
            self.publish(*artifact(1))
        body, release = artifact(3)
        for changes in [dict(applicationId='other.app'), dict(url='/downloads/../token'), dict(versionCode=True), dict(unknown='field')]:
            with self.assertRaises(module.Rejected):
                self.publish(body, dict(release, **changes))
        self.assertEqual(self.current(), before)

    def test_budget_refuses_without_removing_history(self):
        for index in range(1, 9):
            self.publish(*artifact(index))
        before = self.current()
        with self.assertRaises(module.Rejected) as failure:
            self.publish(*artifact(9))
        self.assertEqual(failure.exception.code, 'HISTORY_BUDGET_EXCEEDED')
        self.assertEqual(self.current(), before)
        with patch.object(module, 'MAX_BYTES', 1), self.assertRaises(module.Rejected):
            module.leaves(before[0])

    def test_manifest_commit_failure_rolls_back_only_request_files(self):
        self.publish(*artifact())
        before = self.current()
        with patch.object(module.os, 'replace', side_effect=OSError('public fixture failure')), self.assertRaises(OSError):
            self.publish(*artifact(2))
        self.assertEqual(self.current(), before)

    def test_after_commit_io_error_never_deletes_referenced_apk(self):
        body, release = artifact()
        with patch.object(module, 'sync_directory', side_effect=[None, OSError('public fixture failure')]), self.assertRaises(OSError):
            self.publish(body, release)
        self.assertEqual(self.current()[0]['sha256'], release['sha256'])
        self.assertEqual(self.publish(body, release)['sha256'], release['sha256'])

    def test_unknown_files_and_crash_residue_are_preserved(self):
        unknown = self.publisher.downloads / 'unknown.txt'
        unknown.write_text('retain')
        with self.assertRaises(module.Rejected):
            self.publish(*artifact())
        self.assertEqual(unknown.read_text(), 'retain')
        unknown.unlink()
        residue = self.publisher.staging / 'upload-interrupted'
        residue.mkdir()
        with self.assertRaises(module.Rejected):
            module.Publisher(self.root)
        self.assertTrue(residue.is_dir())

    def test_cross_process_lock_does_not_wait_forever(self):
        with module.process_lock(self.root):
            with self.assertRaises(OSError):
                with module.process_lock(self.root):
                    self.fail('Second owner acquired active lock')

    def test_json_rejects_duplicate_keys_and_nonfinite_values(self):
        for body in [b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":Infinity}', b'[]\xff']:
            with self.assertRaises(module.Rejected):
                module.parse_json(body)

    def test_initialize_copies_verified_snapshot_and_never_overwrites(self):
        self.publish(*artifact())
        manifest = self.current()[0]
        (self.root / 'download.html').write_text(manifest['url'] + manifest['sha256'], encoding='utf-8')
        with tempfile.TemporaryDirectory() as target:
            module.initialize(Path(target), self.root)
            self.assertEqual(module.read_manifest(Path(target) / 'downloads', True)[0], manifest)
            with self.assertRaises(module.Rejected):
                module.initialize(Path(target), self.root)


class ArchiveTest(unittest.TestCase):
    setUp = PublisherTest.setUp
    tearDown = PublisherTest.tearDown
    current = PublisherTest.current
    publish = PublisherTest.publish

    def seed(self):
        selected = []
        for version in [1, 2]:
            for abi in ['arm64-v8a', 'x86_64']:
                body, item = artifact(version, abi)
                self.publish(body, item, alternative=abi == 'x86_64')
                if version == 1:
                    selected.append(item)
        return selected

    def selection(self, items):
        return dict(formatVersion=1, sha256=[item['sha256'] for item in items])

    def test_archive_exact_pair_preserves_current_and_recoverable_bytes(self):
        selected = self.seed()
        before, revision = self.current()
        saved = {path.name: path.read_bytes() for path in self.publisher.downloads.iterdir()}
        result, receipt = self.publisher.archive(self.selection(selected), revision)
        self.assertEqual(result, dict(before, history=[]))
        self.assertEqual(self.current()[0], result)
        directory = self.publisher.archives / receipt['archiveId']
        self.assertEqual(module.archive_record(directory, True)[0], receipt)
        self.assertEqual((directory / 'release.before.json').read_bytes(), saved['release.json'])
        self.assertTrue(receipt['complete'])
        for item in selected:
            for key in ['url', 'checksumUrl']:
                name = Path(item[key]).name
                self.assertEqual((directory / 'downloads' / name).read_bytes(), saved[name])
                self.assertFalse((self.publisher.downloads / name).exists())
        self.assertEqual(list(self.publisher.staging.iterdir()), [])
        self.assertEqual(module.Publisher(self.root).archive_inventory()[0], [receipt])
        with self.assertRaises(module.Rejected):
            self.publisher.archive(self.selection(selected), self.current()[1])
        self.assertEqual(len(list(self.publisher.archives.iterdir())), 1)

    def test_archiving_one_item_preserves_other_history_exactly(self):
        selected = self.seed()
        before, revision = self.current()
        result, _ = self.publisher.archive(self.selection(selected[:1]), revision)
        self.assertEqual(result, dict(before, history=selected[1:]))
        self.assertEqual(self.current()[0], result)

    def test_current_primary_and_alternatives_are_protected(self):
        self.seed()
        before, revision = self.current()
        for item in [before] + before['alternatives']:
            with self.assertRaises(module.Rejected) as failure:
                self.publisher.archive(self.selection([item]), revision)
            self.assertEqual(failure.exception.code, 'ARCHIVE_ACTIVE_RELEASE_FORBIDDEN')
        self.assertEqual(self.current(), (before, revision))
        self.assertEqual(list(self.publisher.archives.iterdir()), [])

    def test_invalid_and_unknown_selection_never_creates_transaction(self):
        selected = self.seed()
        _, revision = self.current()
        sha = selected[0]['sha256']
        for value in [None, [], {}, dict(formatVersion=True, sha256=[sha]), dict(formatVersion=1, sha256=[]),
                      dict(formatVersion=1, sha256=[sha] * 2), dict(formatVersion=1, sha256=['F' * 64]),
                      dict(formatVersion=1, sha256=['f' * 64]), dict(formatVersion=1, sha256=[sha], path='../other')]:
            with self.assertRaises(module.Rejected):
                self.publisher.archive(value, revision)
        self.assertEqual(list(self.publisher.archives.iterdir()), [])
        self.assertEqual(list(self.publisher.staging.iterdir()), [])

    def test_stale_revision_and_capacity_refuse_before_copy(self):
        selected = self.seed()
        before = self.current()
        with self.assertRaises(module.Rejected) as failure:
            self.publisher.archive(self.selection(selected), '"' + 'f' * 64 + '"')
        self.assertEqual(failure.exception.code, 'REVISION_CONFLICT')
        for name, value in [('MAX_ARCHIVES', 0), ('MAX_ARCHIVE_BYTES', 1)]:
            with patch.object(module, name, value), self.assertRaises(module.Rejected) as failure:
                self.publisher.archive(self.selection(selected), before[1])
            self.assertEqual(failure.exception.code, 'ARCHIVE_CAPACITY_EXCEEDED')
        with patch.object(module.shutil, 'disk_usage', return_value=shutil._ntuple_diskusage(100, 100, 0)), self.assertRaises(module.Rejected) as failure:
            self.publisher.archive(self.selection(selected), before[1])
        self.assertEqual(failure.exception.code, 'INSUFFICIENT_SPACE')
        self.assertEqual(self.current(), before)
        self.assertEqual(list(self.publisher.staging.iterdir()), [])

    def test_failure_before_commit_preserves_all_public_files_and_blocks_writes(self):
        selected = self.seed()
        before = self.current()
        with patch.object(module.os, 'replace', side_effect=OSError('synthetic commit failure')), self.assertRaises(OSError):
            self.publisher.archive(self.selection(selected), before[1])
        self.assertEqual(self.current(), before)
        records, _ = self.publisher.archive_inventory()
        self.assertEqual(len(records), 1)
        self.assertFalse(records[0]['complete'])
        directory = self.publisher.archives / records[0]['archiveId']
        module.archive_record(directory, True)
        with self.assertRaises(module.Rejected):
            self.publish(*artifact(3))
        with self.assertRaises(module.Rejected):
            module.Publisher(self.root)
        self.assertTrue(any(self.publisher.staging.iterdir()))

    def test_after_commit_failure_keeps_verified_backup_and_inspection_marker(self):
        selected = self.seed()
        before, revision = self.current()
        original = module.sync_directory
        def fail_after_commit(path):
            if path == self.publisher.downloads:
                raise OSError('synthetic post-commit failure')
            original(path)
        with patch.object(module, 'sync_directory', side_effect=fail_after_commit), self.assertRaises(OSError):
            self.publisher.archive(self.selection(selected), revision)
        raw = json.loads((self.publisher.downloads / 'release.json').read_bytes())
        self.assertEqual(raw, dict(before, history=[]))
        records, _ = self.publisher.archive_inventory()
        self.assertFalse(records[0]['complete'])
        module.archive_record(self.publisher.archives / records[0]['archiveId'], True)
        self.assertTrue(any(self.publisher.staging.iterdir()))
        with self.assertRaises(module.Rejected):
            module.Publisher(self.root)

    def test_removing_marker_does_not_bypass_incomplete_archive(self):
        selected = self.seed()
        with patch.object(module.os, 'replace', side_effect=OSError('synthetic commit failure')), self.assertRaises(OSError):
            self.publisher.archive(self.selection(selected), self.current()[1])
        marker = next(self.publisher.staging.iterdir())
        marker.rename(self.root / 'inspection-marker')
        with self.assertRaises(module.Rejected) as failure:
            module.Publisher(self.root)
        self.assertEqual(failure.exception.code, 'INTERRUPTED_ARCHIVE_REQUIRES_INSPECTION')
        with self.assertRaises(module.Rejected):
            self.publish(*artifact(3))

    def test_partial_public_cleanup_retains_both_originals_in_private_archive(self):
        selected = self.seed()
        original = Path.unlink
        refused = self.publisher.downloads / Path(selected[0]['checksumUrl']).name
        def unlink(path, *args, **kwargs):
            if path == refused:
                raise OSError('synthetic cleanup failure')
            return original(path, *args, **kwargs)
        with patch.object(Path, 'unlink', unlink), self.assertRaises(OSError):
            self.publisher.archive(self.selection(selected), self.current()[1])
        records, _ = self.publisher.archive_inventory()
        module.archive_record(self.publisher.archives / records[0]['archiveId'], True)
        self.assertTrue(refused.is_file())
        self.assertTrue(any(self.publisher.staging.iterdir()))

    def test_unknown_archive_files_and_corruption_are_not_deleted(self):
        selected = self.seed()
        _, receipt = self.publisher.archive(self.selection(selected), self.current()[1])
        directory = self.publisher.archives / receipt['archiveId']
        unknown = directory / 'unknown.txt'
        unknown.write_bytes(b'preserve')
        with self.assertRaises(module.Rejected):
            self.publisher.archive_inventory()
        self.assertEqual(unknown.read_bytes(), b'preserve')
        unknown.unlink()
        apk = directory / 'downloads' / Path(selected[0]['url']).name
        apk.write_bytes(b'x' * apk.stat().st_size)
        with self.assertRaises(ValueError):
            module.archive_record(directory, True)


class HttpTest(unittest.TestCase):
    def setUp(self):
        PublisherTest.setUp(self)
        self.server = module.Server(0, self.publisher, 'a' * 64)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        PublisherTest.tearDown(self)

    def current(self):
        return PublisherTest.current(self)

    def request(self, method='GET', body=None, headers=None, path=module.RELEASE_PATH):
        client = http.client.HTTPConnection('127.0.0.1', getattr(self, 'front_port', self.server.server_port), timeout=3)
        try:
            if headers is None:
                headers = self.signed_headers(method, path, body)
            client.request(method, path, body, headers)
            response = client.getresponse()
            content = response.read()
            signature = response.getheader('X-Publish-Signature')
            if response.status == 200:
                self.assertIsNotNone(signature)
            if signature:
                expected = module.response_signature('a' * 64, (method, path, headers['X-Publish-Nonce'], headers['X-Publish-Signature']), response.status, content)
                self.assertEqual(signature, expected)
            return response.status, json.loads(content)
        finally:
            client.close()

    def signed_headers(self, method='GET', path=module.RELEASE_PATH, body=None, changes=None):
        nonce = secrets.token_hex(16)
        ticket = '-'
        if path != module.CHALLENGE_PATH:
            challenge_headers = self.signed_headers(path=module.CHALLENGE_PATH)
            nonce = challenge_headers['X-Publish-Nonce']
            status, response = self.request(headers=challenge_headers, path=module.CHALLENGE_PATH)
            self.assertEqual(status, 200)
            ticket = response['challenge']
        headers = {'X-Publish-Nonce': nonce, 'X-Publish-Challenge': ticket,
                   'X-Publish-Body-Sha256': hashlib.sha256(body or b'').hexdigest()}
        headers.update(changes or {})
        headers['X-Publish-Signature'] = module.request_signature('a' * 64, method, path, headers['X-Publish-Nonce'], headers['X-Publish-Challenge'],
            headers.get('Content-Type', ''), headers.get('Content-Length', str(len(body or b''))), headers.get('X-Release-Metadata', ''), headers.get('If-Match', ''), headers['X-Publish-Body-Sha256'])
        return headers

    def upload_headers(self, body, item, changes=None):
        values = {'Content-Type': 'application/vnd.android.package-archive', 'If-Match': self.current()[1],
                  'X-Release-Metadata': base64.b64encode(module.encoded(dict(release=item, alternative=False, certificateSha256=module.CERTIFICATE))).decode('ascii')}
        values.update(changes or {})
        return self.signed_headers('POST', body=body, changes=values)

    def test_http_authentication_and_origin_fail_before_publication(self):
        for headers in [{}, {'Authorization': 'Bearer wrong'}, {'Authorization': 'Bearer ' + 'a' * 64, 'Origin': 'https://untrusted.invalid'}]:
            status, _ = self.request(headers=headers)
            self.assertIn(status, [401, 404])
        self.assertFalse(self.current()[0]['available'])
        self.assertEqual(self.request(headers={'Authorization': 'Bearer ' + 'a' * 64}, path='/api/admin/releases')[0], 404)

    def test_http_upload_and_readback(self):
        body, item = artifact()
        status, response = self.request('POST', body, self.upload_headers(body, item))
        self.assertEqual(status, 200)
        self.assertTrue(response['ok'])
        self.assertEqual(self.request()[1]['release']['sha256'], item['sha256'])

    def seed_archive(self):
        for version in [1, 2]:
            for abi in ['arm64-v8a', 'x86_64']:
                body, item = artifact(version, abi)
                self.publisher.upload(io.BytesIO(body), item, self.current()[1], abi == 'x86_64')
        return self.current()[0]['history']

    def archive_headers(self, body, changes=None):
        values = {'Content-Type': 'application/json', 'If-Match': self.current()[1]}
        values.update(changes or {})
        return self.signed_headers('POST', module.ARCHIVE_PATH, body, values)

    def test_signed_archive_and_receipt_readback_without_restart(self):
        selected = self.seed_archive()
        self.assertIn('archive-history-v1', self.request()[1]['capabilities'])
        body = module.encoded(dict(formatVersion=1, sha256=[item['sha256'] for item in selected]))
        headers = self.archive_headers(body)
        status, result = self.request('POST', body, headers, module.ARCHIVE_PATH)
        self.assertEqual(status, 200)
        self.assertTrue(result['archive']['complete'])
        self.assertEqual(self.request(path=module.ARCHIVE_PATH)[1]['archives'], [result['archive']])
        self.assertEqual(self.request('POST', body, headers, module.ARCHIVE_PATH)[0], 409)
        self.assertEqual(self.current()[0]['history'], [])

    def test_archive_checks_actual_body_revision_type_and_size(self):
        selected = self.seed_archive()
        body = module.encoded(dict(formatVersion=1, sha256=[selected[0]['sha256']]))
        before = self.current()
        for changes, expected in [({'Content-Type': 'text/plain'}, 415), ({'If-Match': ''}, 428),
                                  ({'If-Match': '"' + 'f' * 64 + '"'}, 409), ({'X-Release-Metadata': 'e30='}, 415),
                                  ({'X-Publish-Body-Sha256': 'f' * 64}, 400)]:
            headers = self.archive_headers(body, changes)
            self.assertEqual(self.request('POST', body, headers, module.ARCHIVE_PATH)[0], expected)
        changed = body.replace(selected[0]['sha256'].encode('ascii'), b'f' * 64)
        self.assertEqual(self.request('POST', changed, self.archive_headers(body), module.ARCHIVE_PATH)[1]['code'], 'SIGNED_DIGEST_MISMATCH')
        large = b' ' * 2049
        self.assertEqual(self.request('POST', large, self.archive_headers(large), module.ARCHIVE_PATH)[0], 413)
        self.assertEqual(self.current(), before)

    def test_archive_auth_method_path_busy_and_body_tampering_are_rejected(self):
        selected = self.seed_archive()
        body = module.encoded(dict(formatVersion=1, sha256=[selected[0]['sha256']]))
        headers = self.archive_headers(body)
        self.assertEqual(self.request('POST', body, headers, module.RELEASE_PATH)[0], 401)
        self.assertEqual(self.request('GET', body, headers, module.ARCHIVE_PATH)[0], 401)
        self.assertEqual(self.request('POST', body, {}, module.ARCHIVE_PATH)[0], 401)
        with self.publisher.busy:
            self.assertEqual(self.request('POST', body, self.archive_headers(body), module.ARCHIVE_PATH)[0], 409)
        self.assertEqual(len(self.current()[0]['history']), 2)

    def archive_cli(self, arguments, executable):
        key = self.root / 'synthetic-key.txt'
        key.write_text('a' * 64, encoding='ascii')
        config = self.root / 'synthetic-config.json'
        config.write_text(json.dumps(dict(endpoint='http://127.0.0.1:' + str(self.server.server_port) + module.RELEASE_PATH,
                                         tokenFile=str(key))), encoding='utf-8')
        script = Path(__file__).resolve().parents[2] / 'scripts' / 'archive-apk.ps1'
        return subprocess.run([executable, '-NoProfile', '-File', str(script), '-Config', str(config), *arguments],
            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=40)

    def test_powershell_archive_preview_confirmation_and_signed_readback(self):
        executables = list(dict.fromkeys(filter(None, [shutil.which('pwsh'), shutil.which('powershell.exe')])))
        if not executables:
            self.skipTest('PowerShell not installed; archive CLI interoperability untested')
        for executable in executables:
            with self.subTest(executable=executable):
                # Each shell operates on fresh exact history identities, never a production server.
                next_version = self.current()[0].get('versionCode', 0) + 1
                for version in [next_version, next_version + 1]:
                    for abi in ['arm64-v8a', 'x86_64']:
                        body, item = artifact(version, abi)
                        self.publisher.upload(io.BytesIO(body), item, self.current()[1], abi == 'x86_64')
                before, revision = self.current()
                hashes = ','.join(item['sha256'] for item in before['history'])
                preview = self.archive_cli(['-Sha256', hashes], executable)
                self.assertEqual(preview.returncode, 0, preview.stdout + preview.stderr)
                self.assertIn('ARCHIVE_PREVIEW', preview.stdout)
                self.assertEqual(self.current(), (before, revision))
                refused = self.archive_cli(['-Sha256', hashes, '-ConfirmArchive'], executable)
                self.assertNotEqual(refused.returncode, 0)
                stale = self.archive_cli(['-Sha256', hashes, '-Revision', 'f' * 64, '-ConfirmArchive'], executable)
                self.assertNotEqual(stale.returncode, 0)
                self.assertEqual(self.current(), (before, revision))
                completed = self.archive_cli(['-Sha256', hashes, '-Revision', revision.strip('"'), '-ConfirmArchive'], executable)
                self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
                self.assertIn('ARCHIVE_OK', completed.stdout)
                self.assertNotIn('a' * 64, completed.stdout + completed.stderr)
                self.assertEqual(self.current()[0]['history'], [])
                listing = self.archive_cli(['-ListArchives'], executable)
                self.assertEqual(listing.returncode, 0, listing.stdout + listing.stderr)
                self.assertIn('archiveId', listing.stdout)

    def test_http_invalid_metadata_size_and_revision(self):
        body, item = artifact()
        for changes, expected in [({'X-Release-Metadata': 'invalid'}, 400), ({'If-Match': 'bad'}, 428),
                                  ({'If-Match': '"' + 'f' * 64 + '"'}, 409), ({'Content-Type': 'text/plain'}, 415),
                                  ({'Content-Length': '1'}, 413), ({'Transfer-Encoding': 'chunked'}, 411)]:
            headers = self.upload_headers(body, item, changes)
            self.assertEqual(self.request('POST', body, headers)[0], expected)
        self.assertFalse(self.current()[0]['available'])

    def test_concurrent_request_is_rejected_not_queued(self):
        with self.publisher.busy:
            self.assertEqual(self.request()[0], 409)

    def test_challenge_and_release_replay_are_rejected(self):
        headers = self.signed_headers(path=module.CHALLENGE_PATH)
        self.assertEqual(self.request(headers=headers, path=module.CHALLENGE_PATH)[0], 200)
        self.assertEqual(self.request(headers=headers, path=module.CHALLENGE_PATH)[0], 409)
        headers = self.signed_headers()
        self.assertEqual(self.request(headers=headers)[0], 200)
        self.assertEqual(self.request(headers=headers)[0], 409)

    def test_signed_request_fields_cannot_be_changed(self):
        body, item = artifact()
        for field, value in [('X-Release-Metadata', 'e30='), ('If-Match', '"' + 'f' * 64 + '"'),
                             ('X-Publish-Nonce', '0' * 32), ('X-Publish-Challenge', '0' * 64),
                             ('X-Publish-Body-Sha256', '0' * 64), ('Content-Length', '1'), ('Content-Type', 'text/plain')]:
            headers = self.upload_headers(body, item)
            headers[field] = value
            self.assertEqual(self.request('POST', body, headers)[0], 401)
        headers = self.signed_headers()
        self.assertEqual(self.request('POST', b'', headers)[0], 401)
        self.assertEqual(self.request(headers=headers, path=module.RELEASE_PATH + '?x=1')[0], 404)
        self.assertFalse(self.current()[0]['available'])

    def test_digest_header_and_actual_bytes_are_both_checked(self):
        body, item = artifact()
        headers = self.upload_headers(body, item, {'X-Publish-Body-Sha256': 'f' * 64})
        self.assertEqual(self.request('POST', body, headers)[1]['code'], 'SIGNED_DIGEST_MISMATCH')
        headers = self.upload_headers(body, item)
        self.assertEqual(self.request('POST', b'x' * len(body), headers)[1]['code'], 'APK_DIGEST_MISMATCH')
        self.assertFalse(self.current()[0]['available'])

    def test_duplicate_signed_headers_are_rejected(self):
        headers = self.signed_headers()
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=3)
        try:
            connection.putrequest('GET', module.RELEASE_PATH)
            for key, value in headers.items():
                connection.putheader(key, value)
            connection.putheader('X-Publish-Nonce', headers['X-Publish-Nonce'])
            connection.endheaders()
            response = connection.getresponse()
            self.assertEqual(response.status, 400)
            response.read()
        finally:
            connection.close()

    def powershell(self, mode, executable=None):
        executable = executable or shutil.which('pwsh') or shutil.which('powershell')
        if not executable:
            self.skipTest('PowerShell not installed; direct protocol interoperability remains untested')
        body, item = artifact()
        apk = self.root / 'fixture.apk'
        apk.write_bytes(body)
        fixture = self.root / 'fixture.json'
        fixture.write_text(json.dumps(dict(apk=str(apk), release=item)), encoding='utf-8')
        script = Path(__file__).resolve().parents[2] / 'scripts' / 'publish-protocol-test.ps1'
        result = subprocess.run([executable, '-NoProfile', '-File', str(script), '-Endpoint',
            'http://127.0.0.1:' + str(getattr(self, 'front_port', self.server.server_port)) + module.RELEASE_PATH, '-Fixture', str(fixture), '-Mode', mode],
            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=40)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PROTOCOL_OK', result.stdout)

    def test_real_powershell_upload_and_signed_readback(self):
        self.powershell('Publish')
        self.assertTrue(self.current()[0]['available'])

    def test_powershell_rejects_unsigned_or_forged_response(self):
        with patch.object(module, 'response_signature', return_value='0' * 64):
            self.powershell('RejectTamper')
        self.assertFalse(self.current()[0]['available'])

    def test_powershell_rejects_forged_release_after_valid_challenge(self):
        original = module.response_signature
        def signature(key, context, status, body):
            return '0' * 64 if context[1] == module.RELEASE_PATH else original(key, context, status, body)
        with patch.object(module, 'response_signature', side_effect=signature):
            self.powershell('RejectTamper')
        self.assertFalse(self.current()[0]['available'])

    def test_main_connection_check_without_apk_or_android_sdk(self):
        executable = shutil.which('pwsh') or shutil.which('powershell')
        if not executable:
            self.skipTest('PowerShell not installed')
        key = self.root / 'synthetic-key.txt'
        key.write_text('a' * 64, encoding='ascii')
        config = self.root / 'synthetic-config.json'
        config.write_text(json.dumps(dict(endpoint='http://127.0.0.1:' + str(self.server.server_port) + module.RELEASE_PATH,
                                         tokenFile=str(key))), encoding='utf-8')
        script = Path(__file__).resolve().parents[2] / 'scripts' / 'publish-apk.ps1'
        result = subprocess.run([executable, '-NoProfile', '-File', str(script), '-CheckConnection', '-Config', str(config)],
            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=40)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('CONNECT_OK', result.stdout)
        self.assertNotIn('a' * 64, result.stdout + result.stderr)
        self.assertFalse(self.current()[0]['available'])

    def test_windows_powershell_5_interoperability(self):
        executable = shutil.which('powershell.exe')
        if not executable:
            self.skipTest('Windows PowerShell 5 is not available on this host')
        self.powershell('Publish', executable)

    def test_actual_nginx_proxy_preserves_signed_upload(self):
        nginx = shutil.which('nginx')
        if not nginx:
            self.skipTest('Nginx not installed; proxy acceptance remains untested')
        proxy_root = self.root / 'nginx'
        (proxy_root / 'logs').mkdir(parents=True)
        (proxy_root / 'temp').mkdir()
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            self.front_port = probe.getsockname()[1]
        snippet = Path(__file__).resolve().parents[1] / 'publisher' / 'nginx-publish.conf'
        locations = snippet.read_text(encoding='utf-8').replace('127.0.0.1:8765', '127.0.0.1:' + str(self.server.server_port))
        configuration = proxy_root / 'test.conf'
        configuration.write_text('worker_processes 1; error_log logs/error.log; pid logs/nginx.pid; '
            'events { worker_connections 32; } http { access_log off; '
            'client_body_temp_path temp/body; proxy_temp_path temp/proxy; fastcgi_temp_path temp/fastcgi; '
            'uwsgi_temp_path temp/uwsgi; scgi_temp_path temp/scgi; server { listen 127.0.0.1:' + str(self.front_port) + ';\n' + locations + '\n} }', encoding='utf-8')
        arguments = [nginx, '-p', proxy_root.as_posix() + '/', '-c', configuration.as_posix()]
        checked = subprocess.run(arguments + ['-t'], capture_output=True, timeout=10)
        self.assertEqual(checked.returncode, 0, checked.stderr.decode('utf-8', errors='replace'))
        process = subprocess.Popen(arguments + ['-g', 'daemon off; master_process off;'],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            deadline = time.monotonic() + 10
            while True:
                self.assertIsNone(process.poll(), 'Isolated Nginx exited before accepting connections')
                try:
                    with socket.create_connection(('127.0.0.1', self.front_port), timeout=0.5):
                        break
                except OSError:
                    if time.monotonic() >= deadline:
                        self.fail('Isolated Nginx did not become ready')
                    time.sleep(0.05)
            self.powershell('Publish')
            self.assertTrue(self.current()[0]['available'])
            selected = self.seed_archive()
            body = module.encoded(dict(formatVersion=1, sha256=[item['sha256'] for item in selected]))
            status, result = self.request('POST', body, self.archive_headers(body), module.ARCHIVE_PATH)
            self.assertEqual(status, 200)
            self.assertTrue(result['archive']['complete'])
            self.assertEqual(self.request(path=module.ARCHIVE_PATH)[1]['archives'], [result['archive']])
        finally:
            # This is only the single, explicitly spawned test process, never a system service.
            process.terminate()
            process.wait(timeout=10)


class ProtocolTest(unittest.TestCase):
    def test_archive_body_is_complete_and_monotonically_bounded(self):
        self.assertEqual(module.read_archive_body(io.BytesIO(b'{}'), 2), b'{}')
        with self.assertRaises(module.Rejected) as failure:
            module.read_archive_body(io.BytesIO(b'{'), 2)
        self.assertEqual(failure.exception.code, 'INCOMPLETE_ARCHIVE_REQUEST')
        with patch.object(module.time, 'monotonic', side_effect=[0, 1, 16]), self.assertRaises(module.Rejected) as failure:
            module.read_archive_body(io.BytesIO(b'{}'), 2)
        self.assertEqual(failure.exception.code, 'ARCHIVE_READ_TIMEOUT')

    def test_challenge_expiry_capacity_and_restart_invalidation(self):
        challenges = module.Challenges()
        with patch.object(module.time, 'monotonic', return_value=10):
            first = challenges.issue('0' * 32)
            for number in range(1, 64):
                challenges.issue(format(number, '032x'))
            challenges.consume('0' * 32, first['challenge'])
            with self.assertRaises(module.Rejected):
                challenges.consume('0' * 32, first['challenge'])
            with self.assertRaises(module.Rejected) as failure:
                challenges.issue('f' * 32)
            self.assertEqual(failure.exception.code, 'CHALLENGE_CAPACITY')
        with patch.object(module.time, 'monotonic', return_value=130):
            with self.assertRaises(module.Rejected):
                challenges.consume('0' * 32, first['challenge'])
            challenges.issue('f' * 32)
            self.assertEqual(len(challenges.entries), 1)
        with self.assertRaises(module.Rejected):
            module.Challenges().consume('0' * 32, first['challenge'])

    def test_response_binds_status_request_and_exact_bytes(self):
        context = ('GET', module.RELEASE_PATH, '0' * 32, 'a' * 64)
        signature = module.response_signature('a' * 64, context, 200, b'{"ok":true}')
        for changed, status, body in [(context, 500, b'{"ok":true}'), (context, 200, b'{"ok":false}'),
                                      (('POST', *context[1:]), 200, b'{"ok":true}'),
                                      ((context[0], '/other', *context[2:]), 200, b'{"ok":true}'),
                                      ((context[0], context[1], 'b' * 32, context[3]), 200, b'{"ok":true}'),
                                      ((*context[:3], 'b' * 64), 200, b'{"ok":true}')]:
            self.assertNotEqual(module.response_signature('a' * 64, changed, status, body), signature)


if __name__ == '__main__':
    unittest.main()
