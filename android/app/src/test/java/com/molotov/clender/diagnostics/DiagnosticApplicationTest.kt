package com.molotov.clender.diagnostics

import com.molotov.clender.app.ClenderApplication
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class DiagnosticApplicationTest {
    @Test
    fun applicationStartIsDiagnosedWithoutOpeningTheMainContainer() {
        val application = RuntimeEnvironment.getApplication() as ClenderApplication
        assertTrue(application.diagnosticStore.snapshot().decodeToString().contains("APP_START"))
        val field = ClenderApplication::class.java.getDeclaredField("container\$delegate")
        field.isAccessible = true
        assertTrue(!(field.get(application) as Lazy<*>).isInitialized())
    }
}
