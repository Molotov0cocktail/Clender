package com.molotov.clender.ui.app

import androidx.lifecycle.SavedStateHandle
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.state.AppShellViewModel
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCrudShellNavigationTest {
    @Test
    fun replaceTopRouteReplacesNewEventWithCreatedDetailWithoutGrowingStack() {
        val viewModel = AppShellViewModel(SavedStateHandle())
        viewModel.pushRoute(AppRoute.NewEvent(LocalDate.of(2026, 8, 31)))

        viewModel.replaceTopRoute(AppRoute.EventDetail(42))

        assertEquals(listOf(AppRoute.EventDetail(42)), viewModel.state.value.childRoutes)
    }

    @Test
    fun replaceTopRouteRequiresAnExistingChildRoute() {
        val viewModel = AppShellViewModel(SavedStateHandle())

        assertThrows(IllegalStateException::class.java) {
            viewModel.replaceTopRoute(AppRoute.EventDetail(1))
        }
        assertTrue(viewModel.state.value.childRoutes.isEmpty())
    }

    @Test
    fun newDraftIdsAreStrictLowercaseUuidHexAndCanBeCleared() {
        val handle = SavedStateHandle()
        val viewModel = AppShellViewModel(handle)
        val valid = "0123456789abcdef0123456789abcdef"

        viewModel.drafts.set(valid)
        assertEquals(valid, viewModel.state.value.draftId)
        assertEquals(valid, handle.get<String>("draft_id"))

        viewModel.drafts.clear()
        assertNull(viewModel.state.value.draftId)
        assertNull(handle.get<String>("draft_id"))
    }

    @Test
    fun invalidNewDraftIdIsRejectedWithoutPersistingFormContentOrExtraKeys() {
        val handle = SavedStateHandle()
        val viewModel = AppShellViewModel(handle)

        listOf("", "draft-42", "ABCDEF0123456789ABCDEF0123456789", "private title")
            .forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) {
                    viewModel.drafts.set(invalid)
                }
            }

        assertNull(viewModel.state.value.draftId)
        assertTrue(handle.keys().all { it in ALLOWED_KEYS })
        assertTrue(handle.keys().none { it.contains("title") || it.contains("description") })
    }

    private companion object {
        val ALLOWED_KEYS = setOf("destination", "date", "calendar_mode", "draft_id")
    }
}
