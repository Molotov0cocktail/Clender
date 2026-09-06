package com.molotov.clender.ui.about

import com.molotov.clender.app.about.AboutMetadata

data class AboutUiModel(
    val applicationName: String,
    val versionName: String?,
    val versionCode: String?,
    val sandboxText: String = "",
    val webDavText: String = "",
    val aiText: String = ""
) {
    companion object {
        fun fromMetadata(metadata: AboutMetadata): AboutUiModel = AboutUiModel(
            applicationName = metadata.applicationLabel,
            versionName = metadata.versionName,
            versionCode = metadata.versionCode?.toString()
        )
    }
}
