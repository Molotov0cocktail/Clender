package com.molotov.clender.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.molotov.clender.data.settings.BackgroundPolicy

val LocalBackgroundController = staticCompositionLocalOf<BackgroundController?> { null }

@Composable
fun AppBackground(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { backgroundViewModel(context).controller }
    CompositionLocalProvider(
        LocalBackgroundController provides controller,
        LocalContentColor provides MaterialTheme.colorScheme.onBackground
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            controller.bitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().testTag("app_background_image"),
                    contentScale = ContentScale.Crop,
                    alpha = BackgroundPolicy.imageAlpha(controller.selection.strength, dark)
                )
            }
            content()
        }
    }
}
