package com.facefusion.mobile

internal data class MediaFace<T>(
    val person: Int?,
    val thumbnail: T,
    val source: Int?,
    val selectable: Boolean = true,
)

internal class MediaPeople<T : Any> {
    data class Frame<T>(val revision: Long, val faces: List<MediaFace<T>> = emptyList())
    data class Gallery(val records: FloatArray, val owners: IntArray, val sources: IntArray)

    private data class Sample<T>(val record: FloatArray, val thumbnail: T, val quality: Float)
    private class Person<T>(val anchor: Sample<T>, var source: Int = KEEP, val comparison: Boolean = false) {
        val samples = mutableListOf(anchor)
        var best = anchor
    }
    private data class Detection<T>(val sample: Sample<T>, val match: Int)

    private val people = mutableListOf<Person<T>>()
    private var detections = emptyList<Detection<T>>()
    private var revision = -1L
    private var still = false
    var frameAnalyzed = false
        private set

    fun beginFrame(revision: Long) {
        this.revision = revision
        detections = emptyList()
        frameAnalyzed = false
    }

    fun updateFrame(revision: Long, still: Boolean, raw: FloatArray, thumbnail: (FloatArray) -> T?) {
        if (this.revision != revision || raw.size % DETECTION_SIZE != 0) return
        this.still = still
        frameAnalyzed = true
        detections = (0 until raw.size step DETECTION_SIZE).mapNotNull { offset ->
            val record = raw.copyOfRange(offset, offset + RECORD_SIZE)
            val quality = raw[offset + RECORD_SIZE + 1]
            if (!quality.isFinite() || quality <= 0f) return@mapNotNull null
            val thumb = thumbnail(record) ?: return@mapNotNull null
            val match = raw[offset + RECORD_SIZE].toInt()
            val sample = Sample(record, thumb, quality)
            people.getOrNull(match)?.let { learn(it, sample) }
            Detection(sample, match)
        }
    }

    fun visible(): Frame<T> = Frame(revision, detections.map {
        val person = people.getOrNull(it.match)
        MediaFace(it.match.takeIf { person != null }, person?.best?.thumbnail ?: it.sample.thumbnail,
            person?.source?.takeIf { source -> source >= 0 }, it.match != AMBIGUOUS)
    })

    fun assigned(): List<MediaFace<T>> = people.mapIndexedNotNull { id, person ->
        if (person.source < 0) null else MediaFace(id, person.best.thumbnail, person.source)
    }

    fun assignVisible(revision: Long, index: Int, source: Int): Int? {
        if (this.revision != revision || source < 0) return null
        val selected = detections.getOrNull(index) ?: return null
        if (selected.match == AMBIGUOUS) return null
        val matched = people.getOrNull(selected.match)
        if (matched != null) {
            matched.source = source
            return selected.match
        }
        if (people.size >= MAX_PEOPLE) return null
        // Several taps can land before Android recomposes. Compare again against the
        // gallery so the former unknown tile cannot create the same person twice.
        val existing = people.indexOfFirst { person ->
            if (still) person.anchor.record.contentEquals(selected.sample.record)
            else person.samples.any { distance(it.record, selected.sample.record) < NEW_PERSON_DISTANCE }
        }
        if (existing >= 0) return null
        val id = people.size
        people.add(Person(selected.sample, source))
        detections = detections.mapIndexed { i, detection ->
            if (i == index) detection.copy(match = id) else detection
        }
        if (!still) rememberCoVisibleFaces(selected)
        return id
    }

    private fun rememberCoVisibleFaces(selected: Detection<T>) {
        var count = people.count { it.comparison }
        detections = detections.map { detection ->
            val record = detection.sample.record
            if (count >= MAX_COMPARISON_FACES || people.size >= MAX_PEOPLE ||
                detection.match != UNMATCHED ||
                distance(record, selected.sample.record) < 0.13f ||
                people.any { person ->
                    person.samples.any { distance(it.record, record) < 0.13f }
                }) return@map detection
            val id = people.size
            people.add(Person(detection.sample, comparison = true))
            count++
            detection.copy(match = id)
        }
    }

    fun assignKnown(id: Int, source: Int): Boolean {
        val person = people.getOrNull(id) ?: return false
        person.source = source
        return true
    }

    fun clearAssignments() {
        people.forEach { it.source = KEEP }
    }

    fun removeSource(slot: Int) {
        for (person in people) {
            if (person.source == slot) person.source = KEEP
            else if (person.source > slot) person.source--
        }
    }

    fun gallery(): Gallery {
        val count = people.sumOf { it.samples.size }
        val records = FloatArray(count * RECORD_SIZE)
        val owners = IntArray(count)
        var i = 0
        people.forEachIndexed { owner, person ->
            for (sample in person.samples) {
                sample.record.copyInto(records, i * RECORD_SIZE)
                owners[i++] = owner
            }
        }
        return Gallery(records, owners, IntArray(people.size) { people[it].source })
    }

    private fun learn(person: Person<T>, sample: Sample<T>) {
        if (still) return
        // Learn only high-confidence matches near the explicit anchor. Comparing to
        // the anchor on every update rules out a chain of increasingly distant faces.
        if (distance(person.anchor.record, sample.record) > 0.17f) return
        if (sample.quality > person.best.quality) person.best = sample
        if (sample.quality < 0.45f || person.samples.any { distance(it.record, sample.record) < 0.055f })
            return
        if (person.samples.size < MAX_SAMPLES) {
            person.samples.add(sample)
        } else {
            val weakest = (1 until MAX_SAMPLES).minBy { person.samples[it].quality }
            if (sample.quality > person.samples[weakest].quality) person.samples[weakest] = sample
        }
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        for (i in 4 until RECORD_SIZE) dot += a[i].toDouble() * b[i]
        return ((1.0 - dot) * 0.5).toFloat()
    }

    companion object {
        private const val RECORD_SIZE = 4 + 512
        private const val DETECTION_SIZE = RECORD_SIZE + 2
        private const val AMBIGUOUS = -2
        private const val UNMATCHED = -1
        private const val KEEP = -1
        private const val MAX_PEOPLE = 128
        private const val MAX_SAMPLES = 3
        private const val MAX_COMPARISON_FACES = 24
        private const val NEW_PERSON_DISTANCE = 0.33f
    }
}
