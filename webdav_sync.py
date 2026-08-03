"""Validated WebDAV event synchronization without UI or Qt dependencies."""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import datetime
from time import monotonic
from typing import Callable
from urllib.parse import urlsplit, urlunsplit

import requests

import database


REMOTE_FILENAME = "clender-events.json"
SCHEMA_VERSION = 1
MAX_DOCUMENT_BYTES = 5 * 1024 * 1024
MAX_EVENT_RECORDS = 100_000
HTTP_TIMEOUT = (5, 15)
DOWNLOAD_DEADLINE_SECONDS = 20
_UID_RE = re.compile(r"^[0-9a-f]{32}$")
_RECORD_FIELDS = frozenset({
    "sync_uid", "event_type", "title", "start_time", "end_time",
    "description", "estimated_duration", "created_at", "updated_at",
    "deleted_at",
})


class WebDAVError(RuntimeError):
    """Base class for user-safe WebDAV errors."""


class WebDAVTransportError(WebDAVError):
    """A bounded network or HTTP failure."""


class WebDAVDocumentError(WebDAVError):
    """The remote document violated the sync schema."""


class WebDAVConflictError(WebDAVError):
    """The remote ETag changed before a conditional write."""


@dataclass(frozen=True)
class WebDAVSettings:
    enabled: bool
    directory_url: str
    username: str
    password: str

    @property
    def remote_url(self) -> str:
        return self.directory_url + REMOTE_FILENAME

    @classmethod
    def from_mapping(
        cls, mapping: dict, *, require_enabled: bool = True
    ) -> "WebDAVSettings":
        if not isinstance(mapping, dict):
            raise ValueError("WebDAV 设置必须是对象")
        enabled = bool(mapping.get("webdav_enabled", False))
        if require_enabled and not enabled:
            raise ValueError("WebDAV 同步尚未启用")

        raw_url = mapping.get("webdav_url", "")
        username = mapping.get("webdav_username", "")
        password = mapping.get("webdav_password", "")
        if not all(isinstance(value, str) for value in (raw_url, username, password)):
            raise ValueError("WebDAV URL、用户名和密码必须是字符串")
        raw_url = raw_url.strip()
        username = username.strip()
        if not raw_url or not username or not password:
            raise ValueError("WebDAV URL、用户名和密码不能为空")

        parsed = urlsplit(raw_url)
        if parsed.scheme.lower() != "https" or not parsed.netloc:
            raise ValueError("WebDAV URL 必须使用 HTTPS")
        if parsed.username is not None or parsed.password is not None:
            raise ValueError("WebDAV URL 不能包含用户名或密码")
        if parsed.query or parsed.fragment:
            raise ValueError("WebDAV 目录 URL 不能包含查询参数或片段")
        path = parsed.path or "/"
        if not path.endswith("/"):
            path += "/"
        directory_url = urlunsplit(("https", parsed.netloc, path, "", ""))
        return cls(enabled, directory_url, username, password)


@dataclass(frozen=True)
class RemoteSnapshot:
    records: list[dict]
    etag: str | None
    exists: bool


@dataclass(frozen=True)
class SyncResult:
    local_changed: bool
    uploaded: bool
    event_count: int


def _parse_utc_timestamp(value, field: str) -> str:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise WebDAVDocumentError(f"{field} 必须是 UTC 时间戳")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise WebDAVDocumentError(f"{field} 时间格式无效") from exc
    if parsed.utcoffset() is None or parsed.utcoffset().total_seconds() != 0:
        raise WebDAVDocumentError(f"{field} 必须是 UTC 时间戳")
    return parsed.isoformat(timespec="microseconds").replace("+00:00", "Z")


def normalize_record(value) -> dict:
    """Validate and return one canonical, untrusted sync record."""
    if not isinstance(value, dict) or set(value) != _RECORD_FIELDS:
        raise WebDAVDocumentError("日程同步记录字段无效")
    uid = value["sync_uid"]
    if not isinstance(uid, str) or not _UID_RE.fullmatch(uid):
        raise WebDAVDocumentError("日程同步 UUID 无效")
    event_type = value["event_type"]
    if event_type not in ("reminder", "timespan"):
        raise WebDAVDocumentError("日程类型无效")
    title = value["title"]
    description = value["description"]
    duration = value["estimated_duration"]
    if not isinstance(title, str) or not title.strip():
        raise WebDAVDocumentError("日程标题无效")
    if not isinstance(description, str):
        raise WebDAVDocumentError("日程描述无效")
    if isinstance(duration, bool) or not isinstance(duration, int) or duration < 0:
        raise WebDAVDocumentError("日程预计时长无效")
    start_time = value["start_time"]
    end_time = value["end_time"]
    try:
        start = datetime.strptime(start_time, "%Y-%m-%d %H:%M")
    except (TypeError, ValueError) as exc:
        raise WebDAVDocumentError("日程开始时间无效") from exc
    if event_type == "timespan":
        try:
            end = datetime.strptime(end_time, "%Y-%m-%d %H:%M")
        except (TypeError, ValueError) as exc:
            raise WebDAVDocumentError("时间段结束时间无效") from exc
        if end <= start:
            raise WebDAVDocumentError("时间段结束时间必须晚于开始时间")
    elif end_time is not None:
        raise WebDAVDocumentError("提醒事项不能包含结束时间")

    created_at = _parse_utc_timestamp(value["created_at"], "created_at")
    updated_at = _parse_utc_timestamp(value["updated_at"], "updated_at")
    if created_at > updated_at:
        raise WebDAVDocumentError("创建时间不能晚于更新时间")
    deleted_at = value["deleted_at"]
    if deleted_at is not None:
        deleted_at = _parse_utc_timestamp(deleted_at, "deleted_at")
        if deleted_at > updated_at:
            raise WebDAVDocumentError("删除时间不能晚于更新时间")

    return {
        "sync_uid": uid,
        "event_type": event_type,
        "title": title.strip(),
        "start_time": start_time,
        "end_time": end_time,
        "description": description,
        "estimated_duration": duration,
        "created_at": created_at,
        "updated_at": updated_at,
        "deleted_at": deleted_at,
    }


def _normalize_records(records) -> list[dict]:
    if not isinstance(records, list) or len(records) > MAX_EVENT_RECORDS:
        raise WebDAVDocumentError("日程同步记录数量无效")
    normalized = []
    seen = set()
    for record in records:
        item = normalize_record(record)
        if item["sync_uid"] in seen:
            raise WebDAVDocumentError("日程同步文档包含重复 UUID")
        seen.add(item["sync_uid"])
        normalized.append(item)
    return sorted(normalized, key=lambda item: item["sync_uid"])


def _canonical_record(record: dict) -> str:
    return json.dumps(
        record, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    )


def merge_records(local_records, remote_records) -> list[dict]:
    """Merge two validated record sets with deterministic per-event LWW."""
    local = _normalize_records(list(local_records))
    remote = _normalize_records(list(remote_records))
    merged = {item["sync_uid"]: item for item in local}
    for candidate in remote:
        current = merged.get(candidate["sync_uid"])
        if current is None or (
            candidate["updated_at"], _canonical_record(candidate)
        ) > (
            current["updated_at"], _canonical_record(current)
        ):
            merged[candidate["sync_uid"]] = candidate
    return [merged[uid] for uid in sorted(merged)]


def parse_document(payload: bytes) -> list[dict]:
    """Parse and validate the complete remote JSON document."""
    if not isinstance(payload, bytes) or len(payload) > MAX_DOCUMENT_BYTES:
        raise WebDAVDocumentError("WebDAV 日程文档大小无效")
    try:
        root = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise WebDAVDocumentError("WebDAV 日程文档不是有效 JSON") from exc
    if not isinstance(root, dict) or set(root) != {"schema_version", "events"}:
        raise WebDAVDocumentError("WebDAV 日程文档结构无效")
    if root["schema_version"] != SCHEMA_VERSION:
        raise WebDAVDocumentError("不支持的 WebDAV 日程文档版本")
    return _normalize_records(root["events"])


def serialize_document(records) -> bytes:
    """Serialize records into stable UTF-8 JSON after full validation."""
    normalized = _normalize_records(list(records))
    payload = json.dumps(
        {"schema_version": SCHEMA_VERSION, "events": normalized},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    if len(payload) > MAX_DOCUMENT_BYTES:
        raise WebDAVDocumentError("WebDAV 日程文档超过大小限制")
    return payload


class WebDAVClient:
    """Small Basic-auth WebDAV client with conditional writes."""

    def __init__(self, settings: WebDAVSettings, session=None):
        self.settings = settings
        self.session = session or requests

    def _request(self, method: str, url: str, **kwargs):
        headers = dict(kwargs.pop("headers", {}))
        headers.setdefault("User-Agent", "Clender/1.0")
        try:
            return self.session.request(
                method,
                url,
                auth=(self.settings.username, self.settings.password),
                timeout=HTTP_TIMEOUT,
                headers=headers,
                allow_redirects=True,
                **kwargs,
            )
        except (requests.Timeout, requests.ConnectionError) as exc:
            raise WebDAVTransportError("WebDAV 连接或请求超时") from exc
        except requests.RequestException as exc:
            raise WebDAVTransportError("WebDAV 网络请求失败") from exc

    @staticmethod
    def _raise_http(status: int, operation: str):
        if status in (401, 403):
            raise WebDAVTransportError("WebDAV 认证失败或没有访问权限")
        if status == 404:
            raise WebDAVTransportError("WebDAV 目录不存在")
        raise WebDAVTransportError(f"WebDAV {operation} 失败（HTTP {status}）")

    def probe(self) -> None:
        response = self._request(
            "PROPFIND", self.settings.directory_url,
            headers={"Depth": "0"}, stream=True,
        )
        try:
            if response.status_code not in (200, 207):
                self._raise_http(response.status_code, "连接测试")
        finally:
            response.close()

    @staticmethod
    def _read_limited(response) -> bytes:
        payload = bytearray()
        deadline = monotonic() + DOWNLOAD_DEADLINE_SECONDS
        try:
            for chunk in response.iter_content(chunk_size=64 * 1024):
                if monotonic() > deadline:
                    raise WebDAVTransportError("WebDAV 日程文档读取超时")
                if not chunk:
                    continue
                payload.extend(chunk)
                if len(payload) > MAX_DOCUMENT_BYTES:
                    raise WebDAVDocumentError("WebDAV 日程文档超过大小限制")
        except requests.RequestException as exc:
            raise WebDAVTransportError("WebDAV 日程文档读取失败") from exc
        return bytes(payload)

    def fetch(self) -> RemoteSnapshot:
        response = self._request("GET", self.settings.remote_url, stream=True)
        try:
            if response.status_code == 404:
                return RemoteSnapshot([], None, False)
            if response.status_code != 200:
                self._raise_http(response.status_code, "读取")
            records = parse_document(self._read_limited(response))
            etag = response.headers.get("ETag")
            return RemoteSnapshot(records, etag, True)
        finally:
            response.close()

    def put(self, payload: bytes, *, etag: str | None, exists: bool) -> None:
        headers = {"Content-Type": "application/json; charset=utf-8"}
        if exists:
            if not etag:
                raise WebDAVConflictError("远端文件缺少 ETag，无法安全更新")
            headers["If-Match"] = etag
        else:
            headers["If-None-Match"] = "*"
        response = self._request(
            "PUT", self.settings.remote_url, headers=headers, data=payload,
            stream=True,
        )
        try:
            if response.status_code == 412:
                raise WebDAVConflictError("远端日程已被其他设备更新")
            if response.status_code not in (200, 201, 204):
                self._raise_http(response.status_code, "写入")
        finally:
            response.close()


class SyncService:
    """Perform a bounded read/merge/conditional-write synchronization."""

    def __init__(self, client: WebDAVClient):
        self.client = client

    def sync(self, interruption_requested: Callable[[], bool] | None = None) -> SyncResult:
        interrupted = interruption_requested or (lambda: False)
        local_changed = False
        for attempt in range(2):
            if interrupted():
                raise WebDAVTransportError("同步已取消")
            snapshot = self.client.fetch()
            local = _normalize_records(database.get_sync_records())
            merged = merge_records(local, snapshot.records)
            local_changed = database.apply_sync_records(merged) or local_changed
            if interrupted():
                raise WebDAVTransportError("同步已取消")

            # Re-read to include local writes that completed during the merge.
            upload_records = merge_records(database.get_sync_records(), merged)
            if snapshot.exists and upload_records == snapshot.records:
                return SyncResult(local_changed, False, len(upload_records))
            payload = serialize_document(upload_records)
            try:
                self.client.put(payload, etag=snapshot.etag, exists=snapshot.exists)
                return SyncResult(local_changed, True, len(upload_records))
            except WebDAVConflictError:
                if attempt == 1:
                    raise
        raise WebDAVConflictError("远端日程持续发生并发更新")
