package com.facefusion.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaUpgradeTest {
    @Test
    fun seekLeavesFullExportAndAudioBoundsIntact() {
        val timeline = VideoTimeline(10_000)
        val moved = timeline.seek(2_200f).seek(10_001f)
        assertEquals(10_000f, moved.positionMs, 0f)
        assertEquals(0L, moved.startUs)
        assertEquals(Long.MAX_VALUE, moved.endUs)
    }

    @Test
    fun explicitTrimChangesOnlyExportBounds() {
        val trimmed = VideoTimeline(10_000).seek(3_400f).trim(1_000f, 2_000f, true)
        val moved = trimmed.seek(-1f)
        assertEquals(3_400f, trimmed.positionMs, 0f)
        assertEquals(0f, moved.positionMs, 0f)
        assertEquals(1_000_000L, moved.startUs)
        assertEquals(2_000_000L, moved.endUs)
        val short = VideoTimeline(250).trim(240f, 250f, true)
        assertEquals(0L, short.startUs)
        assertEquals(Long.MAX_VALUE, short.endUs)
    }

    @Test
    fun catalogKeepsOrderWhenAnIdentityReturnsOrSomeoneNewEnters() {
        val catalog = MediaPeople<String>()
        fun detection(label: Float, match: Float) = FloatArray(517).apply {
            this[0] = label
            this[516] = match
        }
        val thumbnail: (FloatArray) -> String = { "person ${it[0].toInt()}" }
        catalog.add(detection(1f, -1f), thumbnail)
        catalog.add(detection(1f, 0f) + detection(2f, -1f) + detection(3f, -2f), thumbnail)
        assertEquals(listOf("person 1", "person 2"), catalog.thumbnails())
        assertEquals(1032, catalog.gallery().size)
        assertArrayEquals(intArrayOf(-1, 2), catalog.slots(mapOf(1 to 2)))
        assertArrayEquals(intArrayOf(1, 0), catalog.slots(mapOf(0 to 1, 1 to 0)))
    }
}
