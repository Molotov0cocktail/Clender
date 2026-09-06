package com.molotov.clender.app.about

import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AboutMetadataReaderTest {
    @Test
    fun readsInstalledApplicationMetadataFromPackageManager() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metadata = AboutMetadataReader(context).read()

        assertEquals(
            context.applicationInfo.loadLabel(context.packageManager).toString(),
            metadata.applicationLabel
        )
        assertNotNull(metadata.versionName)
        assertTrue(metadata.versionName!!.isNotBlank())
        assertNotNull(metadata.versionCode)
        assertTrue(metadata.versionCode!! > 0L)
        assertTrue(metadata.versionAvailable)
    }

    @Test
    fun emptyVersionNameIsReportedAsFiniteUnavailableValue() {
        val metadata = AboutMetadataReader.fromPackageInfo(
            packageInfo(versionName = ""),
            applicationLabel = "Clender"
        )

        assertEquals("Clender", metadata.applicationLabel)
        assertFalse(metadata.versionAvailable)
        assertEquals(null, metadata.versionName)
        assertEquals(42L, metadata.versionCode)
    }

    @Test
    fun packageManagerFailureBecomesUnavailableWithoutExceptionText() {
        val secretExceptionText = "private endpoint api-key authorization failure"
        val metadata = AboutMetadataReader.unavailable(
            applicationLabel = "Clender",
            cause = IllegalStateException(secretExceptionText)
        )

        assertFalse(metadata.versionAvailable)
        assertEquals(null, metadata.versionName)
        assertEquals(null, metadata.versionCode)
        assertFalse(metadata.toString().contains(secretExceptionText))
        assertFalse(metadata.toString().contains("IllegalStateException"))
    }

    @Test
    fun malformedPackageMetadataDoesNotExposeUnsafeFields() {
        val metadata = AboutMetadataReader.fromPackageInfo(
            packageInfo(versionName = "1.2.3").apply {
                packageName = "com.molotov.clender"
            },
            applicationLabel = "Clender"
        )

        assertEquals("Clender", metadata.applicationLabel)
        assertEquals("1.2.3", metadata.versionName)
        assertEquals(42L, metadata.versionCode)
        val rendered = metadata.toString()
        listOf(
            "com.molotov.clender",
            "/data/user/0",
            "https://private.example",
            "username",
            "password",
            "authorization",
            "conversation body"
        ).forEach { unsafe -> assertFalse(rendered.contains(unsafe, ignoreCase = true)) }
    }

    @Test
    fun readerConstructionIsLazyAndDoesNotCreateAppRuntimeArtifacts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = File(context.filesDir, "datastore/clender.preferences_pb")
        val database = context.getDatabasePath("clender.db")
        preferences.delete()
        database.delete()

        val reader = AboutMetadataReader(context)

        assertNotNull(reader)
        assertFalse(preferences.exists())
        assertFalse(database.exists())
    }

    @Test
    fun unavailableMetadataHasNoNetworkOrSecretBearingAction() {
        val metadata = AboutMetadataReader.unavailable(
            applicationLabel = "Clender",
            cause = RuntimeException("network https://private.example secret")
        )

        assertFalse(metadata.versionAvailable)
        assertFalse(metadata.toString().contains("http", ignoreCase = true))
        assertFalse(metadata.toString().contains("secret", ignoreCase = true))
        assertFalse(metadata.toString().contains("network", ignoreCase = true))
    }

    private fun packageInfo(versionName: String): PackageInfo = PackageInfo().apply {
        packageName = "com.molotov.clender.test"
        this.versionName = versionName
        versionCode = 42
    }
}
