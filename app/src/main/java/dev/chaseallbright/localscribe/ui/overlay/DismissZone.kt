package dev.chaseallbright.localscribe.ui.overlay

/** Hit test for the drag-to-dismiss target, in screen pixels. */
object DismissZone {
    fun isOver(
        bubbleCenterX: Float,
        bubbleCenterY: Float,
        targetCenterX: Float,
        targetCenterY: Float,
        radiusPx: Float
    ): Boolean {
        val dx = bubbleCenterX - targetCenterX
        val dy = bubbleCenterY - targetCenterY
        return dx * dx + dy * dy <= radiusPx * radiusPx
    }
}
