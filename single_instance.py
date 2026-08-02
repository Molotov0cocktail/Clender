"""Per-user single-instance coordination using Qt local IPC."""
from __future__ import annotations

import getpass
import hashlib

from PyQt5.QtCore import QObject, pyqtSignal
from PyQt5.QtNetwork import QLocalServer, QLocalSocket

from constants import APP_NAME


_ACTIVATE_MESSAGE = b"activate\n"


def _default_service_name() -> str:
    """Return a stable local-server name scoped to the current user."""
    username = (getpass.getuser() or "unknown").strip().casefold()
    digest = hashlib.sha256(username.encode("utf-8")).hexdigest()[:16]
    return f"{APP_NAME}-{digest}"


class SingleInstanceCoordinator(QObject):
    """Elect one primary process and forward activation from later launches."""

    activation_requested = pyqtSignal()

    def __init__(self, service_name: str | None = None, parent=None):
        super().__init__(parent)
        self.service_name = service_name or _default_service_name()
        self._server = QLocalServer(self)
        if hasattr(QLocalServer, "UserAccessOption"):
            self._server.setSocketOptions(QLocalServer.UserAccessOption)
        self._server.newConnection.connect(self._accept_pending_connections)
        self._owns_server = False
        self._connections: set[QLocalSocket] = set()
        self._buffers: dict[QLocalSocket, bytearray] = {}

    def acquire(self, timeout_ms: int = 500) -> bool:
        """Return True for the primary process; notify and reject a secondary."""
        if self._owns_server:
            return True

        timeout_ms = max(0, int(timeout_ms))
        if self._notify_existing(timeout_ms):
            return False
        if self._listen():
            return True

        # A competing process may have won the listen race after our first
        # connection attempt. Recheck before treating the endpoint as stale.
        if self._notify_existing(timeout_ms):
            return False

        QLocalServer.removeServer(self.service_name)
        if self._listen():
            return True

        # Never start a second full application when local IPC cannot be
        # acquired. A final notification also covers a late race winner.
        self._notify_existing(timeout_ms)
        return False

    def close(self) -> None:
        """Release sockets and the server endpoint owned by this process."""
        for socket in tuple(self._connections):
            socket.abort()
            socket.deleteLater()
        self._connections.clear()
        self._buffers.clear()

        if self._owns_server:
            self._server.close()
            self._owns_server = False

    def _listen(self) -> bool:
        if self._server.listen(self.service_name):
            self._owns_server = True
            return True
        return False

    def _notify_existing(self, timeout_ms: int) -> bool:
        socket = QLocalSocket(self)
        socket.connectToServer(self.service_name)
        if not socket.waitForConnected(timeout_ms):
            socket.abort()
            socket.deleteLater()
            return False

        socket.write(_ACTIVATE_MESSAGE)
        socket.flush()
        socket.waitForBytesWritten(timeout_ms)
        socket.disconnectFromServer()
        if socket.state() != QLocalSocket.UnconnectedState:
            socket.waitForDisconnected(timeout_ms)
        socket.deleteLater()
        return True

    def _accept_pending_connections(self) -> None:
        while self._server.hasPendingConnections():
            socket = self._server.nextPendingConnection()
            if socket is None:
                continue
            self._connections.add(socket)
            self._buffers[socket] = bytearray()
            socket.readyRead.connect(lambda current=socket: self._read_socket(current))
            socket.disconnected.connect(
                lambda current=socket: self._release_socket(current)
            )
            self._read_socket(socket)

    def _read_socket(self, socket: QLocalSocket) -> None:
        if socket not in self._buffers:
            return
        self._buffers[socket].extend(bytes(socket.readAll()))
        buffer = self._buffers[socket]
        while b"\n" in buffer:
            raw_message, _, remainder = buffer.partition(b"\n")
            buffer[:] = remainder
            if raw_message == b"activate":
                self.activation_requested.emit()

    def _release_socket(self, socket: QLocalSocket) -> None:
        self._read_socket(socket)
        self._buffers.pop(socket, None)
        self._connections.discard(socket)
        socket.deleteLater()
