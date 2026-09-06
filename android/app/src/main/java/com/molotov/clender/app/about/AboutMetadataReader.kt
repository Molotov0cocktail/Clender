package com.molotov.clender.app.about

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build

/** Safe, non-secret metadata used by the read-only About page. */
data class AboutMetadata(
    val applicationLabel: String,
    val versionName: String?,
    val versionCode: Long?,
    val versionAvailable: Boolean
)

/** Reads only metadata already exposed by the installed application package. */
class AboutMetadataReader(private val context: Context) {
    fun read(): AboutMetadata {
        val packageManager = context.packageManager
        return try {
            val packageInfo = packageManager.getPackageInfo(context.packageName, 0)
            val label = packageManager.getApplicationLabel(
                packageInfo.applicationInfo ?: context.applicationInfo
            ).toString()
            fromPackageInfo(packageInfo, label)
        } catch (_: Exception) {
            unavailable(safeApplicationLabel())
        }
    }

    private fun safeApplicationLabel(): String = try {
        context.applicationInfo.loadLabel(context.packageManager).toString()
    } catch (_: Exception) {
        "Clender"
    }

    companion object {
        fun fromPackageInfo(
            packageInfo: PackageInfo,
            applicationLabel: CharSequence
        ): AboutMetadata {
            val label = applicationLabel.toString()
            val name = packageInfo.versionName?.trim()?.takeIf(String::isNotEmpty)
            val code = packageVersionCode(packageInfo)
            return AboutMetadata(
                applicationLabel = label,
                versionName = name,
                versionCode = code,
                versionAvailable = name != null && code != null
            )
        }

        fun unavailable(
            applicationLabel: CharSequence,
            @Suppress("UNUSED_PARAMETER") cause: Throwable? = null
        ): AboutMetadata = AboutMetadata(
            applicationLabel = applicationLabel.toString(),
            versionName = null,
            versionCode = null,
            versionAvailable = false
        )

        private fun packageVersionCode(packageInfo: PackageInfo): Long? {
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                packageInfo.versionCode.toLong()
            }
            return code.takeIf { it > 0L }
        }
    }
}
