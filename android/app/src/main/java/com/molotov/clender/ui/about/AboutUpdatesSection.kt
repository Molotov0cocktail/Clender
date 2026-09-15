package com.molotov.clender.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.app.about.OfficialReleasePage
import com.molotov.clender.app.about.OfficialReleasePageOpener

@Composable
internal fun AboutUpdatesSection(onOpenReleasePage: ((OfficialReleasePage) -> Boolean)?) {
    val context = LocalContext.current
    val opener = remember(context) { OfficialReleasePageOpener(context) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val openPage: (OfficialReleasePage) -> Unit = { page ->
        failed = !(onOpenReleasePage?.invoke(page) ?: opener.open(page))
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = stringResource(R.string.about_updates_title),
            modifier = Modifier.semantics { heading() }.testTag("about_updates_title"),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = stringResource(R.string.about_updates_hint),
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodyLarge
        )
        OutlinedButton(
            onClick = { openPage(OfficialReleasePage.GITHUB) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("about_update_github")
        ) {
            Text(stringResource(R.string.about_update_github))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { openPage(OfficialReleasePage.GITEE) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("about_update_gitee")
        ) {
            Text(stringResource(R.string.about_update_gitee))
        }
        if (failed) {
            Text(
                text = stringResource(R.string.about_update_open_failed),
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("about_update_error"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
