package com.molotov.clender.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.app.about.AboutMetadataReader
import com.molotov.clender.app.about.OfficialReleasePage

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    AboutScreen(model = rememberAboutUiModel(), modifier = modifier)
}

@Composable
fun AboutScreen(
    model: AboutUiModel,
    modifier: Modifier = Modifier,
    onOpenReleasePage: ((OfficialReleasePage) -> Boolean)? = null
) {
    val title = stringResource(R.string.about_title)
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("about_root")
            .semantics {
                paneTitle = title
                contentDescription = title
            }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() }
                .testTag("about_title"),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        AboutContent(
            model = model,
            onOpenReleasePage = onOpenReleasePage,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

@Composable
private fun AboutContent(
    model: AboutUiModel,
    onOpenReleasePage: ((OfficialReleasePage) -> Boolean)?,
    modifier: Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .testTag("about_content")
    ) {
        AboutField(
            tag = "about_app_name",
            value = stringResource(R.string.about_application_name, model.applicationName)
        )
        AboutField(
            tag = "about_version_name",
            value = stringResource(
                R.string.about_version_name,
                model.versionName ?: stringResource(R.string.about_version_unavailable)
            )
        )
        AboutField(
            tag = "about_version_code",
            value = stringResource(
                R.string.about_version_code,
                model.versionCode ?: stringResource(R.string.about_version_unavailable)
            )
        )
        AboutField(
            tag = "about_sandbox",
            value = model.sandboxText.ifBlank { stringResource(R.string.about_sandbox) }
        )
        AboutField(
            tag = "about_webdav_policy",
            value = model.webDavText.ifBlank { stringResource(R.string.about_webdav_policy) }
        )
        AboutField(
            tag = "about_ai_policy",
            value = model.aiText.ifBlank { stringResource(R.string.about_ai_policy) }
        )
        AboutUpdatesSection(onOpenReleasePage)
    }
}

@Composable
private fun rememberAboutUiModel(): AboutUiModel {
    val context = LocalContext.current
    return remember(context) {
        AboutUiModel.fromMetadata(AboutMetadataReader(context).read())
    }
}

@Composable
private fun AboutField(tag: String, value: String) {
    Text(
        text = value,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag(tag),
        style = MaterialTheme.typography.bodyLarge
    )
}
