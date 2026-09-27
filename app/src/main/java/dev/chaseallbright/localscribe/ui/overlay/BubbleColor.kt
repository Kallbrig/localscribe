package dev.chaseallbright.localscribe.ui.overlay

import kotlin.math.pow

/**
 * Preset colours for the overlay. Presets rather than a free picker so every choice keeps its
 * glyph legible: [contentArgb] is whichever of a near-black or white glyph contrasts more.
 */
enum class BubbleColor(val id: String, val displayName: String, val argb: Long) {
    PURPLE("purple", "Purple", 0xFF6750A4),
    BLUE("blue", "Blue", 0xFF1E6FD9),
    TEAL("teal", "Teal", 0xFF00897B),
    GREEN("green", "Green", 0xFF2E7D32),
    AMBER("amber", "Amber", 0xFFFFB300),
    RED("red", "Red", 0xFFC62828),
    GRAY("gray", "Gray", 0xFF616161),
    BLACK("black", "Black", 0xFF000000),
    WHITE("white", "White", 0xFFFFFFFF);

    val contentArgb: Long
        get() = if (contrastRatio(argb, DARK_CONTENT) > contrastRatio(argb, LIGHT_CONTENT)) {
            DARK_CONTENT
        } else {
            LIGHT_CONTENT
        }

    companion object {
        /** PURPLE is Material's baseline primary -- what the bubble always was. */
        val DEFAULT = PURPLE

        const val DARK_CONTENT = 0xFF1C1B1FL
        const val LIGHT_CONTENT = 0xFFFFFFFFL

        /** WCAG 2 contrast ratio between two opaque colours, 1.0 to 21.0. */
        fun contrastRatio(a: Long, b: Long): Double {
            val la = relativeLuminance(a)
            val lb = relativeLuminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }

        private fun relativeLuminance(argb: Long): Double {
            fun channel(shift: Int): Double {
                val c = ((argb shr shift) and 0xFF) / 255.0
                return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
    }
}
