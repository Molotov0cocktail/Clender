import json
import unittest
from unittest import mock

import requests

from webdav_sync import (
    RemoteSnapshot,
    SyncService,
    WebDAVClient,
    WebDAVConflictError,
    WebDAVDocumentError,
    WebDAVSettings,
    WebDAVTransportError,
    merge_records,
    parse_document,
    serialize_document,
)


def record(uid, updated_at, *, title="事项", deleted_at=None):
    return {
        "sync_uid": uid,
        "event_type": "reminder",
        "title": title,
        "start_time": "2026-08-03 09:00",
        "end_time": None,
        "description": "说明",
        "estimated_duration": 0,
        "created_at": "2026-08-03T00:00:00.000000Z",
        "updated_at": updated_at,
        "deleted_at": deleted_at,
    }


class WebDAVSettingsTests(unittest.TestCase):
    def test_directory_url_is_normalized_to_fixed_remote_file(self):
        settings = WebDAVSettings.from_mapping({
            "webdav_enabled": True,
            "webdav_url": "https://dav.example.test/calendars/user",
            "webdav_username": "用户",
            "webdav_password": "secret",
        })

        self.assertEqual(settings.directory_url, "https://dav.example.test/calendars/user/")
        self.assertEqual(
            settings.remote_url,
            "https://dav.example.test/calendars/user/clender-events.json",
        )

    def test_invalid_or_incomplete_connection_settings_are_rejected(self):
        cases = [
            {"webdav_url": "http://dav.test/path", "webdav_username": "u", "webdav_password": "p"},
            {"webdav_url": "https://dav.test/path?q=1", "webdav_username": "u", "webdav_password": "p"},
            {"webdav_url": "https://dav.test/path#frag", "webdav_username": "u", "webdav_password": "p"},
            {"webdav_url": "https://u:p@dav.test/path", "webdav_username": "u", "webdav_password": "p"},
            {"webdav_url": "https://dav.test/path", "webdav_username": "", "webdav_password": "p"},
            {"webdav_url": "https://dav.test/path", "webdav_username": "u", "webdav_password": ""},
        ]
        for case in cases:
            with self.subTest(case=case), self.assertRaises(ValueError):
                WebDAVSettings.from_mapping(case, require_enabled=False)


class SyncDocumentTests(unittest.TestCase):
    UID_A = "0" * 32
    UID_B = "1" * 32

    def test_document_round_trip_is_canonical_and_sorted(self):
        later = "2026-08-03T02:00:00.000000Z"
        payload = serialize_document([
            record(self.UID_B, later, title="后"),
            record(self.UID_A, later, title="前"),
        ])

        decoded = json.loads(payload.decode("utf-8"))
        self.assertEqual(decoded["schema_version"], 1)
        self.assertEqual(
            [item["sync_uid"] for item in decoded["events"]],
            [self.UID_A, self.UID_B],
        )
        self.assertEqual(parse_document(payload), decoded["events"])

    def test_merge_uses_newest_record_and_preserves_tombstone(self):
        older = "2026-08-03T01:00:00.000000Z"
        newer = "2026-08-03T02:00:00.000000Z"
        local = [record(self.UID_A, older, title="旧")]
        remote = [
            record(self.UID_A, newer, title="已删除", deleted_at=newer),
            record(self.UID_B, older, title="远端新增"),
        ]

        merged = merge_records(local, remote)

        by_uid = {item["sync_uid"]: item for item in merged}
        self.assertEqual(by_uid[self.UID_A]["deleted_at"], newer)
        self.assertEqual(by_uid[self.UID_B]["title"], "远端新增")

    def test_equal_timestamp_conflict_is_deterministic(self):
        stamp = "2026-08-03T02:00:00.000000Z"
        first = record(self.UID_A, stamp, title="甲")
        second = record(self.UID_A, stamp, title="乙")
        self.assertEqual(
            merge_records([first], [second]),
            merge_records([second], [first]),
        )

    def test_untrusted_document_rejects_duplicate_uuid_and_bad_schema(self):
        stamp = "2026-08-03T02:00:00.000000Z"
        duplicate = json.dumps({
            "schema_version": 1,
            "events": [record(self.UID_A, stamp), record(self.UID_A, stamp)],
        }).encode()
        unknown = json.dumps({"schema_version": 2, "events": []}).encode()
        for payload in (duplicate, unknown, b"not-json"):
            with self.subTest(payload=payload[:20]), self.assertRaises(WebDAVDocumentError):
                parse_document(payload)


class WebDAVClientTests(unittest.TestCase):
    def setUp(self):
        self.settings = WebDAVSettings.from_mapping({
            "webdav_enabled": True,
            "webdav_url": "https://dav.example.test/root/",
            "webdav_username": "user",
            "webdav_password": "password",
        })
        self.session = mock.Mock()
        self.client = WebDAVClient(self.settings, session=self.session)

    @staticmethod
    def response(status, *, content=b"", headers=None):
        response = mock.Mock()
        response.status_code = status
        response.content = content
        response.headers = headers or {}
        response.iter_content.return_value = [content]
        return response

    def test_probe_uses_depth_zero_and_basic_credentials(self):
        self.session.request.return_value = self.response(207)
        self.client.probe()
        call = self.session.request.call_args
        self.assertEqual(call.args[:2], ("PROPFIND", self.settings.directory_url))
        self.assertEqual(call.kwargs["headers"]["Depth"], "0")
        self.assertEqual(call.kwargs["auth"], ("user", "password"))

    def test_missing_remote_file_is_an_empty_snapshot(self):
        self.session.request.return_value = self.response(404)
        snapshot = self.client.fetch()
        self.assertEqual(snapshot, RemoteSnapshot(records=[], etag=None, exists=False))

    def test_conditional_put_uses_etag_or_create_precondition(self):
        self.session.request.return_value = self.response(204)
        self.client.put(b"{}", etag='"abc"', exists=True)
        self.assertEqual(self.session.request.call_args.kwargs["headers"]["If-Match"], '"abc"')
        self.client.put(b"{}", etag=None, exists=False)
        self.assertEqual(self.session.request.call_args.kwargs["headers"]["If-None-Match"], "*")

    def test_timeout_and_precondition_failure_are_bounded_errors(self):
        self.session.request.side_effect = requests.Timeout("secret response")
        with self.assertRaises(WebDAVTransportError):
            self.client.fetch()
        self.session.request.side_effect = None
        self.session.request.return_value = self.response(412)
        with self.assertRaises(WebDAVConflictError):
            self.client.put(b"{}", etag='"old"', exists=True)


class SyncServiceTests(unittest.TestCase):
    def test_sync_applies_merge_and_uploads_canonical_document(self):
        uid_local = "a" * 32
        uid_remote = "b" * 32
        stamp = "2026-08-03T02:00:00.000000Z"
        local = [record(uid_local, stamp, title="本地")]
        remote = [record(uid_remote, stamp, title="远端")]
        client = mock.Mock()
        client.fetch.return_value = RemoteSnapshot(remote, '"etag"', True)

        with mock.patch("webdav_sync.database.get_sync_records", return_value=local), mock.patch(
            "webdav_sync.database.apply_sync_records", return_value=True
        ) as apply_records:
            result = SyncService(client).sync()

        merged = apply_records.call_args.args[0]
        self.assertEqual({item["sync_uid"] for item in merged}, {uid_local, uid_remote})
        self.assertEqual(parse_document(client.put.call_args.args[0]), merged)
        self.assertTrue(result.local_changed)

    def test_etag_conflict_retries_only_once(self):
        client = mock.Mock()
        client.fetch.side_effect = [
            RemoteSnapshot([], '"one"', True),
            RemoteSnapshot([], '"two"', True),
        ]
        client.put.side_effect = [WebDAVConflictError("conflict"), None]
        with mock.patch("webdav_sync.database.get_sync_records", return_value=[
            record("a" * 32, "2026-08-03T02:00:00.000000Z")
        ]), mock.patch("webdav_sync.database.apply_sync_records", return_value=False):
            SyncService(client).sync()
        self.assertEqual(client.fetch.call_count, 2)
        self.assertEqual(client.put.call_count, 2)

    def test_identical_remote_document_does_not_issue_redundant_put(self):
        records = [record("c" * 32, "2026-08-03T02:00:00.000000Z")]
        client = mock.Mock()
        client.fetch.return_value = RemoteSnapshot(records, '"same"', True)
        with mock.patch("webdav_sync.database.get_sync_records", return_value=records), mock.patch(
            "webdav_sync.database.apply_sync_records", return_value=False
        ):
            result = SyncService(client).sync()

        self.assertFalse(result.uploaded)
        client.put.assert_not_called()


if __name__ == "__main__":
    unittest.main()
