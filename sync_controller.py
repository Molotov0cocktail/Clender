"""Qt worker orchestration for WebDAV tests and event synchronization."""
from __future__ import annotations

import sqlite3

from PyQt5.QtCore import QObject, QThread, pyqtSignal

import config
from logger import get_logger
from webdav_sync import (
    SyncResult,
    SyncService,
    WebDAVClient,
    WebDAVError,
    WebDAVSettings,
)


_log = get_logger(__name__)


class SyncWorker(QThread):
    """Run one probe or sync operation outside the Qt main thread."""

    completed = pyqtSignal(object)
    failed = pyqtSignal(str)

    def __init__(self, operation: str, settings: WebDAVSettings):
        super().__init__()
        self.operation = operation
        self.settings = settings

    def run(self):
        try:
            client = WebDAVClient(self.settings)
            if self.operation == "probe":
                client.probe()
                self.completed.emit(True)
            elif self.operation == "sync":
                result = SyncService(client).sync(self.isInterruptionRequested)
                self.completed.emit(result)
            else:
                self.failed.emit("未知同步操作")
        except WebDAVError as exc:
            self.failed.emit(str(exc))
        except (OSError, sqlite3.Error, TypeError, ValueError) as exc:
            _log.warning("WebDAV 同步内部失败：%s", type(exc).__name__)
            self.failed.emit("本地日程同步失败")


class SyncController(QObject):
    """Serialize sync work, coalesce changes, and expose main-thread signals."""

    status_changed = pyqtSignal(str)
    schedules_changed = pyqtSignal()
    test_finished = pyqtSignal(bool, str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._worker = None
        self._operation = None
        self._pending_sync = False
        self._shutting_down = False

    def request_sync(self, reason: str = "manual") -> bool:
        """Start or coalesce a sync triggered by a local change or the user."""
        if self._shutting_down:
            return False
        if self._worker is not None:
            if self._operation == "sync":
                self._pending_sync = True
            elif reason == "manual":
                self.status_changed.emit("error:同步服务正忙")
            return False

        mapping = config.load_config()
        if not bool(mapping.get("webdav_enabled", False)):
            if reason == "manual":
                self.status_changed.emit("error:WebDAV 同步尚未启用")
            return False
        try:
            settings = WebDAVSettings.from_mapping(mapping)
        except ValueError as exc:
            if reason == "manual":
                self.status_changed.emit(f"error:{exc}")
            return False
        self.status_changed.emit("working:正在同步日程…")
        self._start_worker("sync", settings)
        return True

    def test_connection(self, mapping: dict) -> bool:
        """Probe transient form settings without persisting or writing remote data."""
        if self._shutting_down:
            return False
        if self._worker is not None:
            self.test_finished.emit(False, "同步服务正忙")
            return False
        try:
            settings = WebDAVSettings.from_mapping(
                mapping, require_enabled=False
            )
        except ValueError as exc:
            self.test_finished.emit(False, str(exc))
            return False
        self.status_changed.emit("working:正在测试 WebDAV 连接…")
        self._start_worker("probe", settings)
        return True

    def _start_worker(self, operation: str, settings: WebDAVSettings) -> None:
        worker = SyncWorker(operation, settings)
        self._worker = worker
        self._operation = operation
        worker.completed.connect(self._on_completed)
        worker.failed.connect(self._on_failed)
        worker.finished.connect(self._on_worker_finished)
        worker.start()

    def _on_completed(self, result) -> None:
        if self._operation == "probe":
            self.test_finished.emit(True, "连接成功")
            self.status_changed.emit("success:WebDAV 连接成功")
            return
        if not isinstance(result, SyncResult):
            self.status_changed.emit("error:同步结果无效")
            return
        if result.local_changed:
            self.schedules_changed.emit()
        if result.uploaded or result.local_changed:
            self.status_changed.emit(
                f"success:日程同步完成（{result.event_count} 项记录）"
            )
        else:
            self.status_changed.emit("unchanged:日程已是最新")

    def _on_failed(self, message: str) -> None:
        safe_message = str(message)[:200] or "同步失败"
        if self._operation == "probe":
            self.test_finished.emit(False, safe_message)
        self.status_changed.emit(f"error:{safe_message}")

    def _on_worker_finished(self) -> None:
        worker = self._worker
        self._worker = None
        self._operation = None
        if worker is not None and hasattr(worker, "deleteLater"):
            worker.deleteLater()
        pending = self._pending_sync
        self._pending_sync = False
        if pending and not self._shutting_down:
            self.request_sync("local-change")

    def shutdown(self) -> None:
        """Prevent new work and give the bounded worker time to finish."""
        self._shutting_down = True
        self._pending_sync = False
        worker = self._worker
        if worker is None:
            return
        worker.requestInterruption()
        worker.wait(60_000)
