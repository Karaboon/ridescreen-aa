// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package io.github.karaboon.ridescreendash

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Decodes the phone's H.264 (Baseline, Annex-B, one access unit per frame, SPS+PPS in front of
 * keyframes — see VideoPipeline on the phone) straight onto [surface].
 *
 * The decoder is created on the first keyframe, configured with its SPS/PPS as csd-0/csd-1: old
 * devices' decoders are pickier about in-band parameter sets than about csd. Frames before that first
 * keyframe can't be decoded and are dropped.
 */
class H264Player(
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    private val log: (String) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var drainThread: Thread? = null
    @Volatile private var running = true
    private var fed = 0L
    private var dropped = 0L
    @Volatile var rendered = 0L
        private set

    /** Feed one access unit. Call from a single thread. */
    fun feed(au: ByteArray) {
        if (!running) return
        val c = codec ?: start(au) ?: run {
            if (dropped++ % 30 == 0L) log("[VIDEO] waiting for a keyframe (SPS/PPS) — dropped $dropped")
            return
        }
        val idx = try { c.dequeueInputBuffer(50_000) } catch (e: IllegalStateException) {
            log("[VIDEO] decoder error: ${e.message}")
            return
        }
        if (idx < 0) {
            // Decoder is behind. Dropping a P-frame smears the picture until the next keyframe (≤ 1 s).
            if (dropped++ % 30 == 0L) log("[VIDEO] decoder busy — dropped $dropped")
            return
        }
        val buf = c.getInputBuffer(idx) ?: return
        buf.clear()
        if (au.size > buf.capacity()) {
            log("[VIDEO] frame ${au.size}b > input buffer ${buf.capacity()}b — dropped")
            c.queueInputBuffer(idx, 0, 0, 0, 0)
            return
        }
        buf.put(au)
        c.queueInputBuffer(idx, 0, au.size, System.nanoTime() / 1000, 0)
        fed++
    }

    private fun start(au: ByteArray): MediaCodec? {
        val (sps, pps) = parameterSets(au) ?: return null
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(width * height, 1 shl 20))
        }
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(fmt, surface, null, 0)
        c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        c.start()
        codec = c
        log("[VIDEO] decoder ${c.name} started ${width}x$height (dropped $dropped before the first keyframe)")
        drainThread = thread(name = "dash-decode-out", isDaemon = true) { drain(c) }
        return c
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val i = try { c.dequeueOutputBuffer(info, 100_000) } catch (e: IllegalStateException) {
                break
            }
            when {
                i >= 0 -> {
                    c.releaseOutputBuffer(i, true)
                    rendered++
                    if (rendered == 1L) log("[VIDEO] first picture on screen")
                }
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> log("[VIDEO] output format ${c.outputFormat}")
            }
        }
    }

    fun release() {
        running = false
        try { drainThread?.join(500) } catch (_: InterruptedException) {}
        val c = codec
        codec = null
        try { c?.stop() } catch (_: Exception) {}
        try { c?.release() } catch (_: Exception) {}
        log("[VIDEO] decoder released (fed $fed, rendered $rendered, dropped $dropped)")
    }

    companion object {
        /** SPS (NAL 7) and PPS (NAL 8) from an Annex-B access unit, each with a 4-byte start code. */
        fun parameterSets(au: ByteArray): Pair<ByteArray, ByteArray>? {
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            for ((start, end) in nalUnits(au)) {
                if (start >= end) continue
                when (au[start].toInt() and 0x1F) {
                    7 -> if (sps == null) sps = withStartCode(au, start, end)
                    8 -> if (pps == null) pps = withStartCode(au, start, end)
                }
            }
            return if (sps != null && pps != null) sps to pps else null
        }

        /** (payloadStart, payloadEnd) of every NAL unit, start codes excluded. */
        fun nalUnits(b: ByteArray): List<Pair<Int, Int>> {
            val starts = ArrayList<Pair<Int, Int>>() // (start-code offset, payload offset)
            var i = 0
            while (i + 2 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
                    if (b[i + 2].toInt() == 1) {
                        starts += i to i + 3; i += 3; continue
                    }
                    if (i + 3 < b.size && b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1) {
                        starts += i to i + 4; i += 4; continue
                    }
                }
                i++
            }
            return starts.mapIndexed { k, (_, payload) ->
                val end = if (k + 1 < starts.size) starts[k + 1].first else b.size
                payload to end
            }
        }

        private fun withStartCode(b: ByteArray, start: Int, end: Int): ByteArray {
            val out = ByteArray(4 + end - start)
            out[3] = 1
            System.arraycopy(b, start, out, 4, end - start)
            return out
        }
    }
}
