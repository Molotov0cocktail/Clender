package com.molotov.clender.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T46-C2b Agent C phase 1: pure draft validation and redaction contract for the WebDAV settings
 * section. Every symbol referenced here is part of the missing C2b contract and does not exist
 * yet; compilation failures are the red light evidence.
 */
class WebDavSettingsDraftContractTest {

    @Test
    fun defaultDraftIsLegalDisabledConfigurationInEveryPasswordState() {
        val draft = WebDavSettingsDraft()
        assertTrue(
            draft.validate(
                passwordConfigured = false,
                passwordInputEmpty = true,
                passwordRemovePending = false
            ).isEmpty()
        )
        assertTrue(
            draft.validate(
                passwordConfigured = true,
                passwordInputEmpty = true,
                passwordRemovePending = false
            ).isEmpty()
        )
        assertTrue(
            draft.validate(
                passwordConfigured = true,
                passwordInputEmpty = false,
                passwordRemovePending = true
            ).isEmpty()
        )
    }

    @Test
    fun enabledValidCredentialsRequirePasswordOnlyWhenMissingAndPendingForcesRemoveError() {
        val draft = validEnabledDraft()
        assertTrue(
            draft.validate(
                passwordConfigured = true,
                passwordInputEmpty = true,
                passwordRemovePending = false
            ).isEmpty()
        )
        assertEquals(
            setOf(WebDavValidationError.PASSWORD_REQUIRED),
            draft.validate(
                passwordConfigured = false,
                passwordInputEmpty = true,
                passwordRemovePending = false
            )
        )
        assertTrue(
            draft.validate(
                passwordConfigured = false,
                passwordInputEmpty = false,
                passwordRemovePending = false
            ).isEmpty()
        )
        assertEquals(
            setOf(WebDavValidationError.REMOVE_WHILE_ENABLED),
            draft.validate(
                passwordConfigured = true,
                passwordInputEmpty = true,
                passwordRemovePending = true
            )
        )
        assertEquals(
            setOf(WebDavValidationError.REMOVE_WHILE_ENABLED),
            draft.validate(
                passwordConfigured = false,
                passwordInputEmpty = false,
                passwordRemovePending = true
            )
        )
    }

    @Test
    fun emptyOrUnparseableUrlIsAttributedToUrlRegardlessOfUsername() {
        assertEquals(
            setOf(WebDavValidationError.URL),
            WebDavSettingsDraft(enabled = true, url = "", username = "alice").validate(
                passwordConfigured = true,
                passwordInputEmpty = true,
                passwordRemovePending = false
            )
        )
        assertEquals(
            setOf(WebDavValidationError.URL),
            WebDavSettingsDraft(enabled = true, url = "   ", username = "alice").validate(
                passwordConfigured = true,
                passwordInputEmpty = false,
                passwordRemovePending = false
            )
        )
        assertEquals(
            setOf(WebDavValidationError.URL),
            WebDavSettingsDraft(enabled = true, url = "not a url", username = "alice").validate(
                passwordConfigured = true,
                passwordInputEmpty = true,
                passwordRemovePending = false
            )
        )
        assertEquals(
            setOf(WebDavValidationError.URL),
            WebDavSettingsDraft(enabled = true, url = "", username = "").validate(
                passwordConfigured = true,
                passwordInputEmpty = true,
                passwordRemovePending = false
            )
        )
    }

    @Test
    fun usernameFailuresWithParseableUrlAreAttributedToUsername() {
        listOf("", "alice:smith", "ali:ce", "bad\uD83D").forEach { username ->
            assertEquals(
                "username=[$username]",
                setOf(WebDavValidationError.USERNAME),
                WebDavSettingsDraft(
                    enabled = true,
                    url = "https://dav.example.com/calendar/",
                    username = username
                ).validate(
                    passwordConfigured = true,
                    passwordInputEmpty = true,
                    passwordRemovePending = false
                )
            )
        }
    }

    @Test
    fun policyFailuresWithParseableUrlAreAttributedToUsernameDeterministically() {
        val badUrls = listOf(
            "http://dav.example.com/calendar/",
            "https://alice:pw@dav.example.com/calendar/",
            "https://dav.example.com/calendar/?a=1",
            "https://dav.example.com//calendar/",
            "https://dav.example.com/../calendar/"
        )
        badUrls.forEach { url ->
            assertEquals(
                "url=[$url]",
                setOf(WebDavValidationError.USERNAME),
                WebDavSettingsDraft(enabled = true, url = url, username = "alice").validate(
                    passwordConfigured = true,
                    passwordInputEmpty = true,
                    passwordRemovePending = false
                )
            )
        }
    }

    @Test
    fun errorsCombineUrlUsernameAndPasswordRulesAndDisabledValuesStillValidate() {
        assertEquals(
            setOf(
                WebDavValidationError.URL,
                WebDavValidationError.PASSWORD_REQUIRED
            ),
            WebDavSettingsDraft(enabled = true, url = "bad url", username = "alice").validate(
                passwordConfigured = false,
                passwordInputEmpty = true,
                passwordRemovePending = false
            )
        )
        assertEquals(
            setOf(WebDavValidationError.USERNAME),
            WebDavSettingsDraft(
                enabled = false,
                url = "https://dav.example.com/calendar/",
                username = "bob:again"
            ).validate(
                passwordConfigured = false,
                passwordInputEmpty = false,
                passwordRemovePending = false
            )
        )
        assertTrue(
            WebDavSettingsDraft(
                enabled = false,
                url = "https://dav.example.com/calendar/",
                username = "alice"
            ).validate(
                passwordConfigured = false,
                passwordInputEmpty = true,
                passwordRemovePending = false
            ).isEmpty()
        )
        assertEquals(
            setOf(WebDavValidationError.URL),
            WebDavSettingsDraft(enabled = false, url = "   ", username = "alice").validate(
                passwordConfigured = false,
                passwordInputEmpty = false,
                passwordRemovePending = false
            )
        )
    }

    @Test
    fun persistedSettingsDefaultsContainBlankWebDavDraft() {
        val persisted = PersistedSettings()
        assertEquals(WebDavSettingsDraft(), persisted.webDav)
        assertFalse(persisted.webDavPasswordConfigured)
    }

    @Test
    fun uiStateToStringNeverContainsWebDavPassword() {
        val secret = SecretInput.from("webdav-print-sentinel".toCharArray())
        val state = SettingsUiState(
            active = true,
            section = SettingsSection.WEBDAV,
            webDavPasswordConfigured = true,
            webDavSecretInput = secret,
            webDavPasswordRemovePending = true
        )
        try {
            assertFalse(state.toString().contains("webdav-print-sentinel"))
        } finally {
            secret.clear()
        }
    }

    @Test
    fun configuredPresenceDoesNotCreateWebDavSecretInput() {
        val state = SettingsUiState(active = true, webDavPasswordConfigured = true)
        assertTrue(state.webDavSecretInput.isEmpty())
        assertTrue(state.webDavPasswordConfigured)
    }

    private fun validEnabledDraft() = WebDavSettingsDraft(
        enabled = true,
        url = "https://dav.example.com/calendar/",
        username = "alice"
    )
}
