package com.facefusion.mobile

internal class MediaPeople<T> {
    data class Person<T>(val record: FloatArray, val thumbnail: T)

    private val people = mutableListOf<Person<T>>()

    fun gallery(): FloatArray = FloatArray(people.size * RECORD_SIZE).also { output ->
        people.forEachIndexed { i, person -> person.record.copyInto(output, i * RECORD_SIZE) }
    }

    fun slots(assignments: Map<Int, Int>): IntArray =
        IntArray(people.size) { assignments[it] ?: KEEP }

    fun add(detections: FloatArray, thumbnail: (FloatArray) -> T?) {
        for (start in 0 until detections.size - RECORD_SIZE step DETECTION_SIZE) {
            if (people.size == MAX_PEOPLE) return
            if (detections[start + RECORD_SIZE] != NEW_IDENTITY) continue
            val record = detections.copyOfRange(start, start + RECORD_SIZE)
            val thumb = thumbnail(record) ?: continue
            people.add(Person(record, thumb))
        }
    }

    fun thumbnails(): List<T> = people.map { it.thumbnail }

    companion object {
        private const val RECORD_SIZE = 4 + 512
        private const val DETECTION_SIZE = RECORD_SIZE + 1
        private const val NEW_IDENTITY = -1f
        private const val MAX_PEOPLE = 128
        private const val KEEP = -1
    }
}
