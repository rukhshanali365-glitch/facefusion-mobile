package com.facefusion.mobile

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * One exact video frame, by timestamp, decoded rather than asked for.
 *
 * ⚠ THIS EXISTS BECAUSE [MediaMetadataRetriever] CANNOT DO IT on every file.
 *
 * Measured on the bench, 2026-08-31, with two clips from the same phone:
 *
 *  * `getFrameAtTime(t, OPTION_CLOSEST)` is a REQUEST, not a contract. On a 12.5 s clip
 *    with a ~5 s keyframe interval it returned just TWO distinct images across the whole
 *    timeline -- every seek between 1.1 s and 4.5 s produced byte-identical pixels in a
 *    freshly allocated Bitmap. On a different clip the same call was exact. So it works
 *    until it does not, and which one you have is a property of the file.
 *  * `getFrameAtIndex(i)` IS exact -- and unusable on exactly the files that need it. It
 *    reads `METADATA_KEY_VIDEO_FRAME_COUNT` internally and throws
 *    `NumberFormatException: s == null` when the container does not publish one. Computing
 *    the count ourselves does not help: the platform never sees our number. The failing
 *    clip above has no frame count; the working one does.
 *
 * That leaves decoding. Seek to the sync frame at or before the target, then decode forward
 * and keep the first frame whose timestamp reaches it -- which is precisely what
 * [VideoSwapper] already does for a real run ("frames between the sync point and the trim
 * mark are decoded but dropped"), so the preview now gets its frame the same way the output
 * does. That is worth more than the convenience of the retriever: a preview that disagrees
 * with the run is not a preview.
 *
 * The cost is real but bounded: a long GOP means decoding up to a keyframe interval of
 * frames per seek. On the clip above that is ~150 frames of hardware decode, well inside the
 * 400 ms the preview already debounces by, and [DEADLINE_MS] stops a pathological file from
 * blocking the pane for ever.
 */
class FrameSeeker private constructor(
    private val extractor: MediaExtractor,
    private val decoder: MediaCodec,
    /** Decoded size, BEFORE rotation. */
    val width: Int,
    val height: Int,
    /** Container rotation, which MediaCodec does not apply. */
    val rotation: Int,
) : Closeable {

    /** Size as the VIEWER sees it, i.e. after rotation. */
    val outWidth: Int get() = if (rotation == 90 || rotation == 270) height else width
    val outHeight: Int get() = if (rotation == 90 || rotation == 270) width else height

    /**
     * The frame at [timeUs], upright, as BGR bytes of [outWidth] x [outHeight].
     *
     * Returns the last frame decoded before the target when the exact one cannot be reached
     * -- end of stream, or the deadline -- because a slightly early frame is a far better
     * answer than an empty pane.
     */
    fun frameAt(timeUs: Long): ByteArray? {
        // From the sync frame at or before the target. The decoder is flushed rather than
        // recreated: a seek invalidates everything queued, and feeding it post-seek samples
        // without a flush decodes them against the wrong reference frames.
        extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        runCatching { decoder.flush() }.getOrElse { return null }

        val cursor = DecodeCursor()
        var best: ByteArray? = null
        var reachedTarget = false
        while (!cursor.ended) {
            val frame = nextFrame(cursor, timeUs, firstAsFallback = best == null) ?: break
            best = frame.bgr
            reachedTarget = frame.ptsUs >= timeUs - SLACK_US
            if (reachedTarget) break
        }
        return if (cursor.ended && timeUs > 0L && best != null && !reachedTarget) null else upright(best)
    }

    /** Decode once in presentation order, converting only frames used by discovery. */
    suspend fun scanFrames(intervalUs: Long, onFrame: suspend (ByteArray) -> Boolean): Boolean {
        val cursor = DecodeCursor()
        var nextUs = 0L
        while (!cursor.ended) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val frame = nextFrame(cursor, nextUs) ?: return cursor.ended
            if (!onFrame(upright(frame.bgr) ?: return false)) return false
            nextUs = frame.ptsUs + intervalUs
        }
        return true
    }

    private class DecodeCursor {
        var inputDone = false
        var ended = false
        val info = MediaCodec.BufferInfo()
    }

    private data class Decoded(val bgr: ByteArray, val ptsUs: Long)

    private fun nextFrame(cursor: DecodeCursor, timeUs: Long, firstAsFallback: Boolean = false): Decoded? {
        val deadline = SystemClock.uptimeMillis() + DEADLINE_MS
        val info = cursor.info
        while (!cursor.ended && SystemClock.uptimeMillis() < deadline) {
            if (!cursor.inputDone) {
                val inIx = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIx >= 0) {
                    val buffer = decoder.getInputBuffer(inIx)
                    val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        cursor.inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIx = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIx < 0) continue
            try {
                cursor.ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val reached = info.presentationTimeUs >= timeUs - SLACK_US
                if (info.size > 0 && (reached || firstAsFallback)) {
                    val bgr = decoder.getOutputImage(outIx)?.use { imageToBgr(it) }
                    if (bgr != null) return Decoded(bgr, info.presentationTimeUs)
                }
            } finally {
                decoder.releaseOutputBuffer(outIx, false)
            }
        }
        return null
    }

    private fun upright(bgr: ByteArray?): ByteArray? {
        if (bgr == null || rotation == 0) return bgr
        return NativePipe.rotateBgr(bgr, width, height, rotation)
    }

    /** Identical to VideoSwapper's, and deliberately so -- same planes, same converter. */
    private fun imageToBgr(image: android.media.Image): ByteArray {
        val p = image.planes
        fun bytes(b: ByteBuffer): ByteArray {
            val a = ByteArray(b.remaining()); b.get(a); return a
        }
        return NativePipe.yuvToBgr(
            bytes(p[0].buffer), p[0].rowStride,
            bytes(p[1].buffer), p[1].rowStride, p[1].pixelStride,
            bytes(p[2].buffer), p[2].rowStride, p[2].pixelStride,
            width, height,
        )
    }

    override fun close() {
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { extractor.release() }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        /** A pathological file must cost one slow seek, never a pane that never fills. */
        private const val DEADLINE_MS = 4_000L
        /** Half a frame at 240 fps: generous enough for timestamp rounding, tight enough
         *  that it cannot match the frame BEFORE the one asked for. */
        private const val SLACK_US = 2_000L

        /** Null when the file has no decodable video track. The caller then falls back. */
        fun open(path: String): FrameSeeker? = runCatching {
            val extractor = MediaExtractor().apply { setDataSource(path) }
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    track = i; fmt = f; break
                }
            }
            val vf = fmt
            if (track < 0 || vf == null) { extractor.release(); return null }
            extractor.selectTrack(track)

            // KEY_ROTATION first, the retriever second -- the same order and the same
            // reasoning as VideoSwapper, which found the format the more reliable source.
            val rotation = when {
                vf.containsKey(MediaFormat.KEY_ROTATION) -> vf.getInteger(MediaFormat.KEY_ROTATION)
                else -> runCatching {
                    val r = MediaMetadataRetriever().apply { setDataSource(path) }
                    val d = r.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    r.release()
                    d
                }.getOrDefault(0)
            }

            val decoder = MediaCodec.createDecoderByType(vf.getString(MediaFormat.KEY_MIME)!!)
            vf.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            decoder.configure(vf, null, null, 0)
            decoder.start()
            FrameSeeker(extractor, decoder,
                        vf.getInteger(MediaFormat.KEY_WIDTH),
                        vf.getInteger(MediaFormat.KEY_HEIGHT),
                        rotation)
        }.getOrNull()
    }
}
