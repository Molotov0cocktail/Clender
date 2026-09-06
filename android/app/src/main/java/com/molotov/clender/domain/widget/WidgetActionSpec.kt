package com.molotov.clender.domain.widget

private const val WIDGET_ACTION_IDENTITY_PREFIX = "clender-internal://widget/"
private const val SIMPLE_ACTION_PART_COUNT = 2
private const val EDIT_ACTION_PART_COUNT = 3
private val CANONICAL_ACTION_ID = Regex("[1-9][0-9]*")

/**
 * Pure identity for an action originating from a configured widget.
 *
 * The identity uses an internal canonical string rather than an
 * external URI. Later platform code may use it as a uniqueness input, but this
 * domain type never creates or executes a platform object.
 */
sealed interface WidgetActionSpec {
    val widgetId: Int
    val canonicalIdentity: String

    data class EditEvent(override val widgetId: Int, val eventId: Int) : WidgetActionSpec {
        init {
            requirePositiveWidgetId(widgetId)
            requirePositiveEventId(eventId)
        }

        override val canonicalIdentity: String
            get() = "$WIDGET_ACTION_IDENTITY_PREFIX$widgetId/edit/$eventId"
    }

    data class QuickAi(override val widgetId: Int) : WidgetActionSpec {
        init {
            requirePositiveWidgetId(widgetId)
        }

        override val canonicalIdentity: String
            get() = "$WIDGET_ACTION_IDENTITY_PREFIX$widgetId/quick-ai"
    }

    data class LocalRefresh(override val widgetId: Int) : WidgetActionSpec {
        init {
            requirePositiveWidgetId(widgetId)
        }

        override val canonicalIdentity: String
            get() = "$WIDGET_ACTION_IDENTITY_PREFIX$widgetId/refresh"
    }

    data class Configure(override val widgetId: Int) : WidgetActionSpec {
        init {
            requirePositiveWidgetId(widgetId)
        }

        override val canonicalIdentity: String
            get() = "$WIDGET_ACTION_IDENTITY_PREFIX$widgetId/configure"
    }

    companion object {
        /**
         * Parses only the exact canonical form. No trimming, URI decoding,
         * query, fragment, alternate scheme, or path normalization is allowed.
         */
        fun parse(raw: String?): WidgetActionSpec? =
            raw?.takeIf(::isCanonicalIdentity)?.let(::parseCanonicalIdentity)
    }
}

private fun isCanonicalIdentity(raw: String): Boolean = raw.isNotEmpty() &&
    raw == raw.trim() && raw.startsWith(WIDGET_ACTION_IDENTITY_PREFIX) &&
    '?' !in raw && '#' !in raw

private fun parseCanonicalIdentity(raw: String): WidgetActionSpec? {
    val parts = raw.removePrefix(WIDGET_ACTION_IDENTITY_PREFIX).split('/')
    val widgetId = parts.firstOrNull()?.let(::parsePositiveId) ?: return null
    return when (parts.getOrNull(1)) {
        "edit" -> parseEditEvent(parts, widgetId)

        "quick-ai" -> parts.takeIf { it.size == SIMPLE_ACTION_PART_COUNT }
            ?.let { WidgetActionSpec.QuickAi(widgetId) }

        "refresh" -> parts.takeIf { it.size == SIMPLE_ACTION_PART_COUNT }
            ?.let { WidgetActionSpec.LocalRefresh(widgetId) }

        "configure" -> parts.takeIf { it.size == SIMPLE_ACTION_PART_COUNT }
            ?.let { WidgetActionSpec.Configure(widgetId) }

        else -> null
    }
}

private fun parseEditEvent(parts: List<String>, widgetId: Int): WidgetActionSpec? =
    parts.takeIf { it.size == EDIT_ACTION_PART_COUNT }
        ?.getOrNull(2)
        ?.let(::parsePositiveId)
        ?.let { eventId -> WidgetActionSpec.EditEvent(widgetId, eventId) }

private fun parsePositiveId(raw: String): Int? = raw.takeIf(CANONICAL_ACTION_ID::matches)
    ?.toIntOrNull()
    ?.takeIf { it > 0 }

private fun requirePositiveWidgetId(value: Int) {
    require(value > 0) { "Widget id must be positive" }
}

private fun requirePositiveEventId(value: Int) {
    require(value > 0) { "Event id must be positive" }
}
