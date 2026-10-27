import base64
import hashlib
import http.client
import importlib.util
import io
import json
from pathlib import Path
import secrets
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('backup_server', Path(__file__).with_name('project-backup-server.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
wire = module.wire


def fixture():
    body = module.MAGIC + secrets.token_bytes(128)
    return body, dict(id=secrets.token_hex(16), label=base64.b64encode(secrets.token_bytes(40)).decode(),
                     bytes=len(body), sha256=hashlib.sha256(body).hexdigest())


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = module.Store(Path(self.temp.name) / 'snapshots')

    def tearDown(self):
        self.temp.cleanup()

    def upload(self, body, record):
        return self.store.upload(io.BytesIO(body), record, time.monotonic() + 10)

    def test_immutable_upload_inventory_and_exact_delete(self):
        body, record = fixture()
        saved = self.upload(body, record)
        self.assertEqual(self.store.inventory(), ([saved], len(body)))
        with self.assertRaises(wire.Rejected):
            self.upload(body, record)
        with self.assertRaises(wire.Rejected):
            self.store.delete(record['id'], '0' * 64)
        self.assertEqual(self.store.record(record['id']), saved)
        self.store.delete(record['id'], record['sha256'])
        self.assertEqual(self.store.inventory(), ([], 0))

    def test_bad_digest_truncation_and_plaintext_leave_no_staging(self):
        body, record = fixture()
        for data in (body[:-1], b'x' * len(body), module.MAGIC + b'x' * (len(body) - 8)):
            with self.assertRaises(wire.Rejected):
                self.upload(data, record)
            self.assertEqual(list(self.store.directory.iterdir()), [])

    def test_partial_network_reads_preserve_magic(self):
        body, record = fixture()
        class Tiny(io.BytesIO):
            def read1(self, n):
                return super().read(min(n, 3))
        self.store.upload(Tiny(body), record, time.monotonic() + 10)
        self.assertEqual(self.store.inventory()[1], len(body))

    def test_unknown_and_crash_files_are_not_deleted(self):
        unknown = self.store.directory / '.upload-crashed'
        unknown.mkdir()
        (unknown / 'private').write_text('preserve')
        with self.assertRaises(wire.Rejected):
            self.store.inventory()
        self.assertTrue((unknown / 'private').is_file())

    def test_quotas_are_checked_before_writing(self):
        body, record = fixture()
        with patch.object(module, 'MAX_TOTAL', len(body) - 1):
            with self.assertRaises(wire.Rejected):
                self.upload(body, record)
        self.assertEqual(list(self.store.directory.iterdir()), [])
        self.upload(body, record)
        with patch.object(module, 'MAX_ITEMS', 1):
            with self.assertRaises(wire.Rejected):
                self.upload(*fixture())

    def test_deadline_and_invalid_metadata(self):
        body, record = fixture()
        with self.assertRaises(wire.Rejected):
            self.store.upload(io.BytesIO(body), record, time.monotonic() - 1)
        for changed in (dict(record, id='../secret'), dict(record, bytes=True), dict(record, label='!'), dict(record, extra=1)):
            with self.assertRaises(wire.Rejected):
                self.upload(body, changed)
        self.assertEqual(list(self.store.directory.iterdir()), [])

    def test_same_size_disk_corruption_does_not_erase_record(self):
        body, record = fixture()
        self.upload(body, record)
        (self.store.directory / record['id'] / 'snapshot.zgb').write_bytes(b'x' * len(body))
        self.assertEqual(len(self.store.inventory()[0]), 1)


class ProtocolTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.key = secrets.token_hex(32)
        self.server = module.Server(0, module.Store(self.temp.name), self.key)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        self.temp.cleanup()

    def exchange(self, method, path, nonce, ticket, body=b'', metadata='', revision='', key=None, extra=None):
        content_type = 'application/octet-stream' if body else ''
        body_sha = hashlib.sha256(body).hexdigest()
        signature = wire.request_signature(key or self.key, method, path, nonce, ticket, content_type, str(len(body)), metadata, revision, body_sha)
        headers = {'X-Publish-Nonce': nonce, 'X-Publish-Challenge': ticket,
                   'X-Publish-Signature': signature, 'X-Publish-Body-Sha256': body_sha,
                   'Content-Length': str(len(body))}
        if content_type: headers['Content-Type'] = content_type
        if metadata: headers['X-Release-Metadata'] = metadata
        if revision: headers['If-Match'] = revision
        headers.update(extra or {})
        conn = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=3)
        try:
            conn.request(method, path, body=body, headers=headers)
            response = conn.getresponse()
            data = response.read()
            signed = response.getheader('X-Publish-Signature')
            if signed:
                self.assertEqual(signed, wire.response_signature(self.key, (method, path, nonce, signature), response.status, data))
            return response.status, data, signed
        finally:
            conn.close()

    def challenge(self):
        nonce = secrets.token_hex(16)
        status, data, signed = self.exchange('GET', module.CHALLENGE, nonce, '-')
        self.assertEqual(status, 200); self.assertIsNotNone(signed)
        return nonce, json.loads(data)['challenge']

    def request(self, method, path, **kwargs):
        return self.exchange(method, path, *self.challenge(), **kwargs)

    def test_upload_list_download_delete_roundtrip(self):
        body, record = fixture()
        meta = base64.b64encode(wire.encoded(record)).decode()
        self.assertEqual(self.request('POST', module.INDEX, body=body, metadata=meta)[0], 200)
        status, listed, signed = self.request('GET', module.INDEX)
        self.assertEqual(status, 200); self.assertIsNotNone(signed)
        self.assertEqual(json.loads(listed)['records'][0]['id'], record['id'])
        url = module.INDEX + '/' + record['id']
        status, actual, signed = self.request('GET', url, revision='"' + record['sha256'] + '"')
        self.assertEqual((status, actual), (200, body)); self.assertIsNotNone(signed)
        self.assertEqual(self.request('POST', url + '/delete', revision='"' + record['sha256'] + '"')[0], 200)
        self.assertEqual(json.loads(self.request('GET', module.INDEX)[1])['records'], [])

    def test_wrong_key_unsigned_rejection_and_replay(self):
        self.assertEqual(self.exchange('GET', module.CHALLENGE, secrets.token_hex(16), '-', key='0' * 64)[0], 401)
        nonce, ticket = self.challenge()
        self.assertEqual(self.exchange('GET', module.INDEX, nonce, ticket)[0], 200)
        self.assertEqual(self.exchange('GET', module.INDEX, nonce, ticket)[0], 409)
        self.assertEqual(self.exchange('GET', module.CHALLENGE, nonce, '-')[0], 409)

    def test_signature_binds_metadata_and_revision(self):
        nonce, ticket = self.challenge()
        self.assertEqual(self.exchange('GET', module.INDEX, nonce, ticket, extra={'If-Match': 'tampered'})[0], 401)
        self.assertEqual(self.exchange('GET', module.INDEX, nonce, ticket, extra={'X-Release-Metadata': 'tampered'})[0], 401)

    def test_not_public_no_bearer_origin_or_traversal(self):
        for extra in ({'Authorization': 'Bearer public-fixture'}, {'Origin': 'http://example.test'}):
            self.assertEqual(self.request('GET', module.INDEX, extra=extra)[0], 401)
        self.assertEqual(self.request('GET', module.INDEX + '/../secret')[0], 404)
        self.assertEqual(self.request('GET', '/downloads/release.json')[0], 404)

    def test_changed_digest_blocks_delete_and_download(self):
        body, record = fixture()
        self.server.store.upload(io.BytesIO(body), record, time.monotonic() + 10)
        url = module.INDEX + '/' + record['id']
        for method, suffix in [('GET', ''), ('POST', '/delete')]:
            self.assertEqual(self.request(method, url + suffix, revision='"' + '0' * 64 + '"')[0], 409)
        self.assertEqual(len(self.server.store.inventory()[0]), 1)

    def test_corrupt_download_is_signed_failure_not_accepted(self):
        body, record = fixture()
        self.server.store.upload(io.BytesIO(body), record, time.monotonic() + 10)
        (self.server.store.directory / record['id'] / 'snapshot.zgb').write_bytes(b'x' * len(body))
        status, data, signed = self.request('GET', module.INDEX + '/' + record['id'], revision='"' + record['sha256'] + '"')
        self.assertEqual(status, 400); self.assertIsNotNone(signed)
        self.assertEqual(json.loads(data)['code'], 'BACKUP_DIGEST_MISMATCH')

    def test_challenge_expiry_and_restart(self):
        nonce, ticket = self.challenge()
        with patch.object(wire.time, 'monotonic', return_value=time.monotonic() + 121):
            self.assertEqual(self.exchange('GET', module.INDEX, nonce, ticket)[0], 401)
        self.server.challenges = wire.Challenges()
        self.assertEqual(self.exchange('GET', module.INDEX, nonce, ticket)[0], 401)


if __name__ == '__main__':
    unittest.main()
