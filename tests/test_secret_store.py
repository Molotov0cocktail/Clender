import os
import unittest
from types import SimpleNamespace
from unittest import mock

import secret_store


class NotFoundError(Exception):
    winerror = 1168


class NoLogonSessionError(Exception):
    winerror = 1312


class SecretStoreTests(unittest.TestCase):
    def test_environment_variable_has_priority(self):
        with mock.patch.dict(os.environ, {secret_store.ENV_NAME: " env-secret "}):
            self.assertEqual(secret_store.get_api_key(), "env-secret")

    def test_missing_windows_credential_returns_empty(self):
        backend = SimpleNamespace(CRED_TYPE_GENERIC=1)
        backend.CredRead = mock.Mock(side_effect=NotFoundError())
        with mock.patch.object(secret_store.os, "name", "nt"), mock.patch.object(
            secret_store, "_win32cred", return_value=backend
        ), mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual(secret_store.get_api_key(), "")

    def test_set_writes_generic_persistent_credential(self):
        backend = SimpleNamespace(
            CRED_TYPE_GENERIC=1,
            CRED_PERSIST_LOCAL_MACHINE=2,
            CRED_PERSIST_SESSION=1,
            CredWrite=mock.Mock(),
        )
        with mock.patch.object(secret_store.os, "name", "nt"), mock.patch.object(
            secret_store, "_win32cred", return_value=backend
        ):
            secret_store.set_api_key("secret")
        credential = backend.CredWrite.call_args.args[0]
        self.assertEqual(credential["TargetName"], secret_store.TARGET_NAME)
        self.assertEqual(credential["CredentialBlob"], "secret")

    def test_unicode_credential_blob_round_trip(self):
        value = "密钥-🔐"
        backend = SimpleNamespace(
            CRED_TYPE_GENERIC=1,
            CRED_PERSIST_LOCAL_MACHINE=2,
            CRED_PERSIST_SESSION=1,
            CredWrite=mock.Mock(),
        )
        with mock.patch.object(secret_store.os, "name", "nt"), mock.patch.object(
            secret_store, "_win32cred", return_value=backend
        ):
            secret_store.set_api_key(value)
        blob = backend.CredWrite.call_args.args[0]["CredentialBlob"].encode("utf-16-le")
        read_backend = SimpleNamespace(
            CRED_TYPE_GENERIC=1,
            CredRead=mock.Mock(return_value={"CredentialBlob": blob}),
        )
        with mock.patch.object(secret_store.os, "name", "nt"), mock.patch.object(
            secret_store, "_win32cred", return_value=read_backend
        ), mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual(secret_store.get_api_key(), value)

    def test_no_logon_session_falls_back_to_session_credential(self):
        backend = SimpleNamespace(
            CRED_TYPE_GENERIC=1,
            CRED_PERSIST_LOCAL_MACHINE=2,
            CRED_PERSIST_SESSION=1,
            CredWrite=mock.Mock(side_effect=[NoLogonSessionError(), None]),
        )
        with mock.patch.object(secret_store.os, "name", "nt"), mock.patch.object(
            secret_store, "_win32cred", return_value=backend
        ):
            secret_store.set_api_key("secret")
        self.assertEqual(backend.CredWrite.call_count, 2)
        retry_credential = backend.CredWrite.call_args.args[0]
        self.assertEqual(retry_credential["Persist"], backend.CRED_PERSIST_SESSION)


if __name__ == "__main__":
    unittest.main()
