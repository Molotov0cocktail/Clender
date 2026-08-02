"""Secure API-key storage backed by Windows Credential Manager."""
from __future__ import annotations

import getpass
import logging
import os


_log = logging.getLogger(__name__)
TARGET_NAME = "Clender/API"
ENV_NAME = "CLENDER_API_KEY"


class SecretStoreError(RuntimeError):
    """Raised when a secret cannot be persisted securely."""


def _win32cred():
    try:
        import win32cred
    except ImportError as exc:  # pragma: no cover - depends on host packaging
        raise SecretStoreError("pywin32 is required for Windows Credential Manager") from exc
    return win32cred


def get_api_key() -> str:
    """Return the environment override or the key stored in Credential Manager."""
    env_value = os.environ.get(ENV_NAME, "").strip()
    if env_value:
        return env_value
    if os.name != "nt":
        return ""

    win32cred = _win32cred()
    try:
        credential = win32cred.CredRead(
            TARGET_NAME, win32cred.CRED_TYPE_GENERIC, 0
        )
    except Exception as exc:
        if getattr(exc, "winerror", None) == 1168:
            return ""
        _log.warning("读取 Windows 凭据失败: %s", exc)
        return ""

    blob = credential.get("CredentialBlob", "")
    if isinstance(blob, bytes):
        for encoding in ("utf-16-le", "utf-8"):
            try:
                return blob.decode(encoding).rstrip("\x00")
            except UnicodeDecodeError:
                continue
        return ""
    return str(blob)


def set_api_key(api_key: str) -> None:
    """Persist a non-empty API key securely, or delete the stored key."""
    value = api_key.strip()
    if not value:
        delete_api_key()
        return
    if os.name != "nt":
        raise SecretStoreError(
            f"非 Windows 环境不持久化 API Key；请设置环境变量 {ENV_NAME}"
        )

    win32cred = _win32cred()
    credential = {
        "Type": win32cred.CRED_TYPE_GENERIC,
        "TargetName": TARGET_NAME,
        "UserName": getpass.getuser(),
        # pywin32 311 requires str on write and returns UTF-16LE bytes on read.
        "CredentialBlob": value,
        "Persist": win32cred.CRED_PERSIST_LOCAL_MACHINE,
        "Comment": "Clender OpenAI-compatible API key",
    }
    try:
        try:
            win32cred.CredWrite(credential, 0)
        except Exception as exc:
            # Some Windows sessions (including restricted desktop sessions)
            # reject persistent credentials with ERROR_NO_SUCH_LOGON_SESSION.
            # Session persistence still survives application restarts during
            # the current Windows sign-in and is preferable to plaintext.
            if getattr(exc, "winerror", None) != 1312:
                raise
            credential["Persist"] = win32cred.CRED_PERSIST_SESSION
            win32cred.CredWrite(credential, 0)
            _log.warning("持久凭据不可用，API Key 已保存为当前登录会话凭据")
    except Exception as exc:  # pragma: no cover - real backend failure
        raise SecretStoreError("无法写入 Windows Credential Manager") from exc


def delete_api_key() -> None:
    """Delete the persisted API key if one exists."""
    if os.name != "nt":
        return
    win32cred = _win32cred()
    try:
        win32cred.CredDelete(TARGET_NAME, win32cred.CRED_TYPE_GENERIC, 0)
    except Exception as exc:
        if getattr(exc, "winerror", None) != 1168:
            raise SecretStoreError("无法删除 Windows Credential Manager 凭据") from exc
