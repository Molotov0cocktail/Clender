package com.molotov.clender.ui.ai

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Local text formatting only: HTML and URLs remain inert text. */
internal fun assistantText(content: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    while (cursor < content.length) {
        val opening = content.indexOf("**", cursor)
        val closing = if (opening >= 0) content.indexOf("**", opening + 2) else -1
        if (opening < 0 || closing <= opening + 2) {
            append(content.substring(cursor))
            break
        }
        append(content.substring(cursor, opening))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(content.substring(opening + 2, closing))
        }
        cursor = closing + 2
    }
}
