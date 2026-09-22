package com.lastwave.app.playback.analysis

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Headless audio decoder that uses Android's [MediaExtractor] + [MediaCodec]
 * to decode the first N seconds of a YouTube audio stream at CPU speed
 * (typically 80–300 ms for 3 seconds of audio), without requiring an
 * ExoPlayer instance or an active `AudioSink`.
 *
 * **Why headless?** The standby player is paused (`playWhenReady = false`)
 * during pre-buffering, so ExoPlayer's audio renderer does not push PCM
 * through the audio processor chain. This decoder operates independently,
 * opening a second HTTP connection to the already-resolved stream URL and
 * decoding at unthrottled CPU speed.
 *
 * **Fail-open:** Returns `null` on any error (network, codec, format).
 * The caller treats null as "no analysis available" and the transition
 * engine falls back to the safe `-8dB Bass-Cut Smooth Blend`.
 *
 * **Resource safety:** [MediaExtractor] and [MediaCodec] are always released
 * in `finally` blocks, even on cancellation or exceptions.
 *
 * **Threading:** Runs on [Dispatchers.IO] via `withContext`. The codec
 * operates in synchronous mode with short timeouts to remain responsive
 * to coroutine cancellation.
 */
class HeadlessStreamDecoder {

    companion object {
        private const val TAG = "HeadlessStreamDecoder"

        /** Duration of audio to decode (seconds). */
        const val DEFAULT_DECODE_SECONDS = 3.0f

        /** Codec dequeue timeout in microseconds (10 ms). */
        private const val CODEC_TIMEOUT_US = 10_000L

        /** Maximum wall-clock time for the entire decode operation (ms). */
        private const val MAX_DECODE_WALL_TIME_MS = 8_000L

        /** Audio MIME type prefix for track selection. */
        private const val AUDIO_MIME_PREFIX = "audio/"

        /**
         * Max bytes to pre-read from ExoPlayer's cache for cache-aware decoding.
         * 768 KB covers ~3s of 256kbps AAC or ~6s of 128kbps Opus — more than
         * enough for the 3-second analysis window.
         */
        private const val CACHE_READ_BYTES = 768 * 1024
    }

    /**
     * Decoded audio result containing interleaved Float32 PCM samples.
     *
     * @property samples Interleaved Float32 PCM data.
     * @property sampleRateHz Sample rate in Hz (e.g. 48000, 44100).
     * @property channelCount Number of interleaved channels (1 = mono, 2 = stereo).
     */
    data class DecodedAudio(
        val samples: FloatArray,
        val sampleRateHz: Int,
        val channelCount: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecodedAudio) return false
            return samples.contentEquals(other.samples) &&
                sampleRateHz == other.sampleRateHz &&
                channelCount == other.channelCount
        }

        override fun hashCode(): Int {
            var result = samples.contentHashCode()
            result = 31 * result + sampleRateHz
            result = 31 * result + channelCount
            return result
        }
    }

    /**
     * Decodes the first [decodeSeconds] of audio from [url].
     *
     * @param url Direct audio stream URL (already resolved by InnerTube).
     * @param headers HTTP request headers (e.g. authorization, range tokens).
     * @param decodeSeconds Duration of audio to decode.
     * @return [DecodedAudio] with Float32 samples, or `null` on failure.
     */
    suspend fun decode(
        url: String,
        headers: Map<String, String> = emptyMap(),
        decodeSeconds: Float = DEFAULT_DECODE_SECONDS,
    ): DecodedAudio? = withContext(Dispatchers.IO) {
        val startTimeMs = System.currentTimeMillis()
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(url, headers)
            decodeFromExtractor(extractor, decodeSeconds, startTimeMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Headless decode failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { extractor?.release() }
        }
    }

    /**
     * Cache-aware variant: reads audio bytes from ExoPlayer's [SimpleCache]
     * via [CacheDataSource] instead of opening a second HTTP connection.
     *
     * By the time this method runs, ExoPlayer's `standby.prepare()` has been
     * buffering the stream for several seconds, so the first few hundred KB
     * should already be on disk. This reduces decode time from **7-8 seconds**
     * (network-bound) to **~200 ms** (disk + codec only).
     *
     * **Fallback:** If the cache read fails or yields too few bytes, returns
     * `null`. The caller should fall back to [decode] with the direct URL.
     *
     * @param cacheDataSourceFactory Factory for creating cache-backed data sources.
     * @param uri The stream URL (used as the cache lookup key alongside [cacheKey]).
     * @param cacheKey The cache key matching ExoPlayer's DataSpec key.
     * @param headers HTTP request headers for the upstream data source (used only on cache miss).
     * @param decodeSeconds Duration of audio to decode.
     * @return [DecodedAudio] with Float32 samples, or `null` on failure.
     */
    suspend fun decodeFromCache(
        cacheDataSourceFactory: CacheDataSource.Factory,
        uri: String,
        cacheKey: String,
        headers: Map<String, String> = emptyMap(),
        decodeSeconds: Float = DEFAULT_DECODE_SECONDS,
    ): DecodedAudio? = withContext(Dispatchers.IO) {
        val startTimeMs = System.currentTimeMillis()
        var mediaDataSource: ByteArrayMediaDataSource? = null
        var extractor: MediaExtractor? = null
        try {
            // ── Read from ExoPlayer's disk cache ─────────────────────────
            val dataSpec = DataSpec.Builder()
                .setUri(Uri.parse(uri))
                .setKey(cacheKey)
                .setHttpRequestHeaders(headers)
                .build()
            val cacheDs = cacheDataSourceFactory.createDataSource()
            val bytes: ByteArray
            try {
                cacheDs.open(dataSpec)
                val buffer = ByteArray(CACHE_READ_BYTES)
                var totalRead = 0
                while (totalRead < CACHE_READ_BYTES) {
                    val read = cacheDs.read(buffer, totalRead, CACHE_READ_BYTES - totalRead)
                    if (read == C.RESULT_END_OF_INPUT) break
                    totalRead += read
                }
                bytes = buffer.copyOf(totalRead)
            } finally {
                runCatching { cacheDs.close() }
            }

            if (bytes.size < 1024) {
                Log.d(TAG, "Cache read too small (${bytes.size} bytes), skipping cache decode")
                return@withContext null
            }
            Log.d(TAG, "Read ${bytes.size} bytes from cache in ${System.currentTimeMillis() - startTimeMs}ms")

            // ── Feed cached bytes to MediaExtractor via MediaDataSource ──
            mediaDataSource = ByteArrayMediaDataSource(bytes)
            extractor = MediaExtractor()
            extractor.setDataSource(mediaDataSource)
            decodeFromExtractor(extractor, decodeSeconds, startTimeMs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Cache decode failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { extractor?.release() }
            runCatching { mediaDataSource?.close() }
        }
    }

    /**
     * Core decode loop shared by [decode] and [decodeFromCache].
     * Extracts audio from an already-configured [MediaExtractor].
     */
    private suspend fun decodeFromExtractor(
        extractor: MediaExtractor,
        decodeSeconds: Float,
        startTimeMs: Long,
    ): DecodedAudio? {
        var codec: MediaCodec? = null
        try {
            val audioTrackIndex = findAudioTrack(extractor) ?: run {
                Log.d(TAG, "No audio track found in stream")
                return null
            }
            extractor.selectTrack(audioTrackIndex)

            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: run {
                Log.d(TAG, "No MIME type in audio track format")
                return null
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val targetSamples = (decodeSeconds * sampleRate * channelCount).toInt()

            if (targetSamples <= 0 || sampleRate <= 0 || channelCount <= 0) {
                Log.d(TAG, "Invalid audio format: rate=$sampleRate ch=$channelCount")
                return null
            }

            val accumulated = ArrayList<Float>(targetSamples)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false

            while (!outputEos && accumulated.size < targetSamples) {
                currentCoroutineContext().ensureActive()

                if (System.currentTimeMillis() - startTimeMs > MAX_DECODE_WALL_TIME_MS) {
                    Log.d(TAG, "Wall-clock timeout after ${MAX_DECODE_WALL_TIME_MS}ms")
                    break
                }

                if (!inputEos) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val bytesRead = extractor.readSampleData(inputBuffer, 0)
                            if (bytesRead < 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEos = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(
                                    inputIndex, 0, bytesRead, presentationTimeUs, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEos = true
                        }
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val remaining = targetSamples - accumulated.size
                            extractPcmSamples(
                                outputBuffer, bufferInfo, codec, accumulated, remaining
                            )
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "Output format changed: ${codec.outputFormat}")
                    }
                }
            }

            if (accumulated.isEmpty()) {
                Log.d(TAG, "No samples decoded")
                return null
            }

            val elapsed = System.currentTimeMillis() - startTimeMs
            Log.d(TAG, "Decoded ${accumulated.size} samples in ${elapsed}ms " +
                "(rate=$sampleRate, ch=$channelCount)")

            return DecodedAudio(
                samples = accumulated.toFloatArray(),
                sampleRateHz = sampleRate,
                channelCount = channelCount,
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    /**
     * Finds the first audio track in the [extractor].
     * @return Track index, or null if no audio track exists.
     */
    private fun findAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith(AUDIO_MIME_PREFIX) == true) return i
        }
        return null
    }

    /**
     * Extracts PCM samples from a [MediaCodec] output buffer and appends
     * them as Float32 values to [out].
     *
     * Handles both PCM_FLOAT and PCM_16BIT (Int16) output formats.
     * Int16 samples are normalized to [-1.0, 1.0].
     */
    private fun extractPcmSamples(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        codec: MediaCodec,
        out: MutableList<Float>,
        maxSamples: Int,
    ) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        val outputFormat = runCatching { codec.outputFormat }.getOrNull()
        val pcmEncoding = outputFormat?.let {
            if (it.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                it.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else null
        }

        var added = 0
        when (pcmEncoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                val floatBuffer = buffer.asFloatBuffer()
                while (floatBuffer.hasRemaining() && added < maxSamples) {
                    out.add(floatBuffer.get())
                    added++
                }
            }
            else -> {
                val shortBuffer = buffer.asShortBuffer()
                while (shortBuffer.hasRemaining() && added < maxSamples) {
                    out.add(shortBuffer.get().toFloat() / Short.MAX_VALUE.toFloat())
                    added++
                }
            }
        }
    }

    /**
     * In-memory [android.media.MediaDataSource] backed by a byte array.
     * Enables [MediaExtractor] to read audio data from ExoPlayer's cache
     * without touching the network.
     */
    private class ByteArrayMediaDataSource(
        private val data: ByteArray,
    ) : android.media.MediaDataSource() {

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val available = minOf(size, (data.size - position.toInt()))
            if (available <= 0) return -1
            System.arraycopy(data, position.toInt(), buffer, offset, available)
            return available
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() { /* no-op, byte array is GC'd */ }
    }
}
