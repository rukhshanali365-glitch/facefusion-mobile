package com.facefusion.mobile

internal data class VideoTimeline(
    val durationMs: Long = 0,
    val startMs: Float = 0f,
    val endMs: Float = durationMs.toFloat(),
    val positionMs: Float = 0f,
) {
    fun seek(position: Float) = copy(positionMs = position.coerceIn(0f, durationMs.toFloat()))

    fun trim(start: Float, end: Float, movingStart: Boolean): VideoTimeline {
        val duration = durationMs.toFloat()
        val minimum = minOf(duration, 333f)
        val safeEnd = end.coerceIn(minimum, duration)
        val safeStart = start.coerceIn(0f, if (movingStart) safeEnd - minimum else duration - minimum)
        val adjustedEnd = safeEnd.coerceAtLeast(safeStart + minimum)
        return copy(startMs = safeStart, endMs = adjustedEnd)
    }

    val startUs: Long get() = (startMs * 1000.0).toLong()
    val endUs: Long get() =
        if (endMs >= durationMs) Long.MAX_VALUE else (endMs * 1000.0).toLong()
}
