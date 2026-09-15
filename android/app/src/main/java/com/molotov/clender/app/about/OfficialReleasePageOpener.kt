package com.molotov.clender.app.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

enum class OfficialReleasePage(val url: String) {
    GITHUB("https://github.com/Molotov0cocktail/Clender/releases/latest"),
    GITEE("https://gitee.com/Molotov0coaktail/clender/releases")
}

/** Opens an official release page only in response to an explicit UI action. */
class OfficialReleasePageOpener(private val context: Context) {
    fun open(page: OfficialReleasePage): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, page.url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
