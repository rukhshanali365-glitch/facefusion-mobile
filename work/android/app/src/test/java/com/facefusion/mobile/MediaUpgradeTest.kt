package com.facefusion.mobile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun detection(
        label: Int, a: Float = 1f, b: Float = 0f, match: Int = -1, quality: Float = 0.5f,
    ) = FloatArray(518).apply {
        this[0] = label.toFloat()
        this[4] = a
        this[5] = b
        this[516] = match.toFloat()
        this[517] = quality
    }

    private fun thumb(record: FloatArray) = "face ${record[0].toInt()}"

    @Test
    fun visibleFacesFollowOnlyLatestSettledFrame() {
        val people = MediaPeople<String>()
        people.beginFrame(1)
        people.updateFrame(1, false, detection(1) + detection(2, 0f, 1f), ::thumb)
        assertEquals(listOf("face 1", "face 2"), people.visible().faces.map { it.thumbnail })
        people.beginFrame(2)
        assertTrue(people.visible().faces.isEmpty())
        assertFalse(people.frameAnalyzed)
        people.updateFrame(1, false, detection(9), ::thumb)
        assertTrue(people.visible().faces.isEmpty())
        people.updateFrame(2, false, detection(3) + detection(4, quality = 0f), ::thumb)
        assertEquals(listOf("face 3"), people.visible().faces.map { it.thumbnail })
        assertNull(people.assignVisible(1, 0, 2))
        assertTrue(people.gallery().sources.isEmpty())
    }

    @Test
    fun assignedFacePersistsOffscreenAndKeepsItsClearestThumbnail() {
        val people = MediaPeople<String>()
        people.beginFrame(1)
        people.updateFrame(1, false, detection(1) + detection(2, 0f, 1f), ::thumb)
        assertEquals(0, people.assignVisible(1, 0, 2))
        assertArrayEquals(intArrayOf(2, -1), people.gallery().sources)
        people.beginFrame(2)
        people.updateFrame(2, false, detection(3, 0f, 1f, match = 1), ::thumb)
        assertEquals(listOf(1), people.visible().faces.map { it.person })
        assertEquals("face 1", people.assigned().single().thumbnail)
        people.beginFrame(3)
        people.updateFrame(3, false, detection(4, 0.8f, 0.6f, match = 0, quality = 0.9f), ::thumb)
        assertEquals("face 4", people.assigned().single().thumbnail)
        assertArrayEquals(intArrayOf(0, 0, 1), people.gallery().owners)
        people.beginFrame(4)
        people.updateFrame(4, false, detection(5, match = 0, quality = 0.4f), ::thumb)
        assertEquals("face 4", people.assigned().single().thumbnail)
        people.removeSource(0)
        assertEquals(1, people.assigned().single().source)
        people.clearAssignments()
        assertTrue(people.assigned().isEmpty())
        assertArrayEquals(intArrayOf(-1, -1), people.gallery().sources)
    }

    @Test
    fun uncertainAndDuplicateObservationsCannotCreateAssignments() {
        val people = MediaPeople<String>()
        people.beginFrame(1)
        people.updateFrame(1, false, detection(1) + detection(2, 0.99f, 0.14f), ::thumb)
        assertEquals(0, people.assignVisible(1, 0, 0))
        assertNull(people.assignVisible(1, 1, 1))
        people.beginFrame(2)
        people.updateFrame(2, false, detection(3, 0.45f, 0.89f, match = -2), ::thumb)
        assertFalse(people.visible().faces.single().selectable)
        assertNull(people.assignVisible(2, 0, 2))
        assertArrayEquals(intArrayOf(0), people.gallery().sources)
    }

    @Test
    fun twoCopiesOfAFaceInAStillCanUseDifferentSources() {
        val people = MediaPeople<String>()
        people.beginFrame(1)
        people.updateFrame(1, true, detection(1) + detection(2), ::thumb)
        assertEquals(0, people.assignVisible(1, 0, 0))
        assertEquals(1, people.assignVisible(1, 1, 1))
        assertArrayEquals(intArrayOf(0, 1), people.gallery().sources)
    }
}
