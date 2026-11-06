// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package io.github.karaboon.ridescreendash

import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The two Carbit EasyConn / PXC wire framings, from the DASH's point of view. Byte layouts are the
 * ones the phone app implements (app/…/PxcFrame.kt, EasyConnProber.mediaLoop) and
 * docs/01-REVERSE-ENGINEERING.md §3–4 documents. All little-endian.
 */
object Pxc {
    // Ports. The dash listens on PROBE; the PHONE listens on the other three and we connect to it.
    const val PORT_PROBE = 10930
    const val PORT_CTRL = 10922        // CmdBaseHead control (opened twice: CAR_CTRL + CAR_DATA)
    const val PORT_MEDIA_CTRL = 10921  // ReqBase media control
    const val PORT_MEDIA_DATA = 10920  // ReqBase media data (frame pull)

    // CmdBaseHead commands (control plane).
    const val MDNS_RESPOND = 0x70000010      // phone → dash probe
    const val MDNS_RESPOND_ACK = 0x70000011  // dash → phone {"status":true}
    const val HEARTBEAT = 0x70000000
    const val HEARTBEAT_ACK = 0x70000001
    const val CH_CAR_CTRL = 0x10000
    const val CH_CAR_DATA = 0x20000
    const val CLIENT_INFO = 0x10010
    const val CLIENT_INFO_RLY = 0x10011
    const val QUERY_SPEED = 0x10690
    const val CHECK_SN = 0x103e0

    // ReqBase media commands (dash → phone requests; reply = request + 1).
    const val REQ_CONFIG_CAPTURE = 16
    const val REQ_GET_VERSION = 48
    const val REQ_HEARTBEAT = 64
    const val REQ_DATA_START = 112
    const val REQ_DATA_NEXT = 114
    const val REQ_TOUCH = 32

    class Frame(val cmd: Int, val payload: ByteArray) {
        val hex: String get() = "0x" + cmd.toUInt().toString(16)
        val text: String get() = if (payload.isEmpty()) "" else String(payload, Charsets.UTF_8)
    }

    class Req(val cmdType: Int, val body: ByteArray)

    /** CmdBaseHead: cmd i32 | totalLen i32 (= 16 + payload) | magic i32 (= cmd xor totalLen) | 0 i32. */
    fun writeFrame(out: OutputStream, cmd: Int, payload: ByteArray = ByteArray(0)) {
        val total = 16 + payload.size
        val h = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(0, cmd).putInt(4, total).putInt(8, cmd xor total)
        synchronized(out) {
            out.write(h.array())
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
        }
    }

    fun writeFrame(out: OutputStream, cmd: Int, json: String) =
        writeFrame(out, cmd, json.toByteArray(Charsets.UTF_8))

    /** One CmdBaseHead frame, or null on a clean EOF. Throws on a corrupt header. */
    fun readFrame(input: InputStream): Frame? {
        val h = ByteArray(16)
        if (!readFully(input, h)) return null
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.getInt(0)
        val total = b.getInt(4)
        if ((cmd xor total) != b.getInt(8)) error("bad CmdBaseHead magic (cmd=$cmd len=$total)")
        val len = total - 16
        if (len < 0 || len > 1 shl 20) error("bad CmdBaseHead length $total")
        val payload = ByteArray(len)
        if (len > 0 && !readFully(input, payload)) error("short CmdBaseHead payload")
        return Frame(cmd, payload)
    }

    /** ReqBase: cmdType i16 | cmdLen u16 | token i32 | body. */
    fun writeReq(out: OutputStream, cmdType: Int, body: ByteArray = ByteArray(0)) {
        val h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        h.putShort(0, cmdType.toShort()).putShort(2, body.size.toShort()).putInt(4, 0)
        synchronized(out) {
            out.write(h.array())
            if (body.isNotEmpty()) out.write(body)
            out.flush()
        }
    }

    /** One ReqBase message, or null on a clean EOF. */
    fun readReq(input: InputStream): Req? {
        val h = ByteArray(8)
        if (!readFully(input, h)) return null
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val type = b.getShort(0).toInt()
        val len = b.getShort(2).toInt() and 0xFFFF
        val body = ByteArray(len)
        if (len > 0 && !readFully(input, body)) error("short ReqBase body")
        return Req(type, body)
    }

    /** Reads exactly buf.size bytes; false on EOF. */
    fun readFully(input: InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Boolean {
        var n = 0
        while (n < len) {
            val r = input.read(buf, off + n, len - n)
            if (r <= 0) return false
            n += r
        }
        return true
    }

    /**
     * Like [readFully], but a socket timeout before the FIRST byte returns null ("nothing came")
     * instead of throwing, so the frame pull can simply ask again. A timeout after some bytes arrived
     * still throws: the stream would be out of step.
     */
    fun readFullyOrIdle(input: InputStream, buf: ByteArray): Boolean? {
        var n = 0
        while (n < buf.size) {
            val r = try {
                input.read(buf, n, buf.size - n)
            } catch (e: SocketTimeoutException) {
                if (n == 0) return null else throw e
            }
            if (r <= 0) return false
            n += r
        }
        return true
    }
}
