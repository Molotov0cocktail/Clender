import os
import unittest
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt5.QtWidgets import QApplication

from sync_controller import SyncController
from webdav_sync import SyncResult


class SignalStub:
    def __init__(self):
        self.callbacks = []

    def connect(self, callback):
        self.callbacks.append(callback)

    def emit(self, *args):
        for callback in tuple(self.callbacks):
            callback(*args)


class WorkerStub:
    def __init__(self, operation, settings):
        self.operation = operation
        self.settings = settings
        self.completed = SignalStub()
        self.failed = SignalStub()
        self.finished = SignalStub()
        self.started = False
        self.interrupted = False

    def start(self):
        self.started = True

    def requestInterruption(self):
        self.interrupted = True

    def wait(self, _timeout):
        return True


class SyncControllerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self.config = {
            "webdav_enabled": True,
            "webdav_url": "https://dav.example.test/root",
            "webdav_username": "user",
            "webdav_password": "password",
        }
        self.workers = []

        def factory(operation, settings):
            worker = WorkerStub(operation, settings)
            self.workers.append(worker)
            return worker

        self.load_patch = mock.patch(
            "sync_controller.config.load_config", side_effect=lambda: dict(self.config)
        )
        self.worker_patch = mock.patch("sync_controller.SyncWorker", side_effect=factory)
        self.load_patch.start()
        self.worker_patch.start()
        self.addCleanup(self.load_patch.stop)
        self.addCleanup(self.worker_patch.stop)
        self.controller = SyncController()
        self.addCleanup(self.controller.shutdown)

    def test_disabled_local_change_does_not_start_worker(self):
        self.config["webdav_enabled"] = False
        self.assertFalse(self.controller.request_sync("local-change"))
        self.assertEqual(self.workers, [])

    def test_manual_sync_emits_status_and_remote_refresh(self):
        statuses = []
        refreshes = []
        self.controller.status_changed.connect(statuses.append)
        self.controller.schedules_changed.connect(lambda: refreshes.append(True))

        self.assertTrue(self.controller.request_sync("manual"))
        worker = self.workers[0]
        worker.completed.emit(SyncResult(local_changed=True, uploaded=True, event_count=2))
        worker.finished.emit()

        self.assertEqual(worker.operation, "sync")
        self.assertEqual(refreshes, [True])
        self.assertTrue(statuses[0].startswith("working"))
        self.assertTrue(statuses[-1].startswith("success"))

    def test_change_while_busy_is_coalesced_into_one_follow_up(self):
        self.controller.request_sync("local-change")
        self.assertFalse(self.controller.request_sync("local-change"))

        first = self.workers[0]
        first.completed.emit(SyncResult(False, True, 1))
        first.finished.emit()

        self.assertEqual(len(self.workers), 2)
        self.assertTrue(self.workers[1].started)

    def test_connection_test_uses_transient_form_settings(self):
        results = []
        self.controller.test_finished.connect(lambda ok, text: results.append((ok, text)))

        self.assertTrue(self.controller.test_connection(dict(self.config)))
        worker = self.workers[0]
        self.assertEqual(worker.operation, "probe")
        worker.completed.emit(True)
        worker.finished.emit()

        self.assertEqual(results, [(True, "连接成功")])


if __name__ == "__main__":
    unittest.main()
