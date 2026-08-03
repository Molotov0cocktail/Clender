import importlib
import os
import unittest
import uuid
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PyQt5.QtNetwork import QLocalSocket
from PyQt5.QtTest import QTest
from PyQt5.QtWidgets import QApplication

import main


def _single_instance_module():
    return importlib.import_module("single_instance")


class SingleInstanceCoordinatorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self):
        self.module = _single_instance_module()
        self.service_name = f"Clender-test-{uuid.uuid4().hex}"
        self.module.QLocalServer.removeServer(self.service_name)
        self.addCleanup(self.module.QLocalServer.removeServer, self.service_name)

    def test_primary_acquires_server_and_close_allows_restart(self):
        primary = self.module.SingleInstanceCoordinator(self.service_name)
        self.addCleanup(primary.close)

        self.assertTrue(primary.acquire(timeout_ms=100))
        primary.close()

        restarted = self.module.SingleInstanceCoordinator(self.service_name)
        self.addCleanup(restarted.close)
        self.assertTrue(restarted.acquire(timeout_ms=100))

    def test_secondary_notifies_primary_and_returns_false(self):
        primary = self.module.SingleInstanceCoordinator(self.service_name)
        secondary = self.module.SingleInstanceCoordinator(self.service_name)
        self.addCleanup(primary.close)
        self.addCleanup(secondary.close)
        activations = []
        primary.activation_requested.connect(lambda: activations.append(True))

        self.assertTrue(primary.acquire(timeout_ms=100))
        self.assertFalse(secondary.acquire(timeout_ms=100))

        for _ in range(20):
            self.app.processEvents()
            if activations:
                break
            QTest.qWait(10)
        self.assertEqual(activations, [True])

    def test_silent_secondary_only_probes_and_does_not_activate_primary(self):
        primary = self.module.SingleInstanceCoordinator(self.service_name)
        secondary = self.module.SingleInstanceCoordinator(self.service_name)
        self.addCleanup(primary.close)
        self.addCleanup(secondary.close)
        activations = []
        primary.activation_requested.connect(lambda: activations.append(True))

        self.assertTrue(primary.acquire(timeout_ms=100))
        self.assertFalse(
            secondary.acquire(timeout_ms=100, activate_existing=False)
        )

        for _ in range(10):
            self.app.processEvents()
            QTest.qWait(10)
        self.assertEqual(activations, [])

    def test_invalid_message_does_not_request_activation(self):
        primary = self.module.SingleInstanceCoordinator(self.service_name)
        self.addCleanup(primary.close)
        activations = []
        primary.activation_requested.connect(lambda: activations.append(True))
        self.assertTrue(primary.acquire(timeout_ms=100))

        socket = QLocalSocket()
        socket.connectToServer(self.service_name)
        self.assertTrue(socket.waitForConnected(100))
        socket.write(b"invalid\n")
        socket.flush()
        socket.waitForBytesWritten(100)
        socket.disconnectFromServer()

        for _ in range(10):
            self.app.processEvents()
            QTest.qWait(10)
        self.assertEqual(activations, [])

    def test_listen_race_rechecks_existing_instance_before_cleanup(self):
        fake_server = mock.Mock()
        fake_server.listen.return_value = False
        with mock.patch.object(
            self.module, "QLocalServer", return_value=fake_server
        ) as server_class:
            coordinator = self.module.SingleInstanceCoordinator(self.service_name)
            coordinator._notify_existing = mock.Mock(side_effect=[False, True])

            self.assertFalse(coordinator.acquire(timeout_ms=100))

        server_class.removeServer.assert_not_called()

    def test_confirmed_stale_endpoint_is_removed_and_listen_retried(self):
        fake_server = mock.Mock()
        fake_server.listen.side_effect = [False, True]
        with mock.patch.object(
            self.module, "QLocalServer", return_value=fake_server
        ) as server_class:
            server_class.removeServer.return_value = True
            coordinator = self.module.SingleInstanceCoordinator(self.service_name)
            coordinator._notify_existing = mock.Mock(side_effect=[False, False])

            self.assertTrue(coordinator.acquire(timeout_ms=100))

        server_class.removeServer.assert_called_once_with(self.service_name)
        self.assertEqual(fake_server.listen.call_count, 2)

    def test_default_service_name_is_stable_and_scoped_by_user(self):
        with mock.patch.object(self.module.getpass, "getuser", return_value="Alice"):
            alice_one = self.module.SingleInstanceCoordinator().service_name
            alice_two = self.module.SingleInstanceCoordinator().service_name
        with mock.patch.object(self.module.getpass, "getuser", return_value="Bob"):
            bob = self.module.SingleInstanceCoordinator().service_name

        self.assertEqual(alice_one, alice_two)
        self.assertNotEqual(alice_one, bob)
        self.assertTrue(alice_one.startswith("Clender-"))


class MainEntrySingleInstanceTests(unittest.TestCase):
    def test_secondary_exits_before_runtime_data_logging_database_or_ui(self):
        app = mock.Mock()
        app.exec_.return_value = 0
        coordinator = mock.Mock()
        coordinator.acquire.return_value = False

        with mock.patch.object(main, "QApplication", return_value=app), mock.patch.object(
            main, "SingleInstanceCoordinator", return_value=coordinator, create=True
        ) as coordinator_class, mock.patch.object(
            main.config, "ensure_app_data_dir"
        ) as ensure_data, mock.patch.object(
            main, "configure_logging"
        ) as configure_logging, mock.patch.object(
            main.database, "init_db"
        ) as init_db, mock.patch.object(
            main, "MainWindow"
        ) as main_window:
            result = main.main(["clender"])

        self.assertEqual(result, 0)
        coordinator_class.assert_called_once_with(parent=app)
        ensure_data.assert_not_called()
        configure_logging.assert_not_called()
        init_db.assert_not_called()
        main_window.assert_not_called()
        app.exec_.assert_not_called()


if __name__ == "__main__":
    unittest.main()
