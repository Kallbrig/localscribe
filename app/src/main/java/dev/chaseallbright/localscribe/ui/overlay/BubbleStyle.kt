package dev.chaseallbright.localscribe.ui.overlay

/** How the overlay looks: one colour and one opacity, applied to every overlay state. */
data class BubbleStyle(
    val color: BubbleColor = BubbleColor.DEFAULT,
    val opacityPercent: Int = BubbleOpacity.DEFAULT_PERCENT
)
