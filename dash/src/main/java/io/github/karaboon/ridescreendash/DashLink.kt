// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package io.github.karaboon.ridescreendash

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The DASH side of a Carbit EasyConn / PXC session — what a bike's head unit does — so the RideScreen
 * phone app treats this device as a touch dash. Flow (docs/01-REVERSE-ENGINEERING.md §2–4):
 *
 *  1. We listen on :10930 (and advertise `_EasyConn._tcp`). The phone sends MDNS_RESPOND; we answer
 *     `{"status":true}`.
 *  2. We connect BACK to the phone: :10922 twice (CAR_CTRL, then CAR_DATA) for the control handshake
 *     (channel selects, CLIENT_INFO, QUERY_SPEED, CHECK_SN), then :10921 for media control
 *     (CONFIG_CAPTURE 16→17, GET_VERSION 48→49, DATA_START 112→113) and :10920 for frames.
 *  3. On :10920 we pull one H.264 access unit per REQ_DATA_NEXT (114); the phone answers RAW
 *     `[size i32 LE][Annex-B AU]` or, when it has no frame within 1.5 s, nothing (we ask again).
 *  4. Touches go to the phone as ReqBase cmdType 32 on :10921 (§ EasyConnProber.handleTouch).
 *
 * Our CLIENT_INFO is shaped so the phone picks its touch + landscape profile ("CFDL26 / MotoPlay
 * Landscape"), which advertises a touchscreen to Android Auto and letterboxes AA into our canvas.
 */
class DashLink(
    private val context: Context,
    /** Canvas we ask the phone for (≈ this screen, capped for old decoders). */
    private val wantW: Int,
    private val wantH: Int,
    private val log: (String) -> Unit,
    private val listener: Listener,
) {
    interface Listener {
        /** The phone answered our probe; the handshake is starting. */
        fun onPhone(phone: String)
        /** Media negotiated; frames of [width]x[height] follow. */
        fun onLinked(width: Int, height: Int)
        /** One H.264 Annex-B access unit (SPS/PPS precede the first keyframe). Called on the pull thread. */
        fun onFrame(au: ByteArray)
        fun onUnlinked(reason: String)
    }

    @Volatile private var running = false
    private var probeServer: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    /** Bumped per session; a thread whose generation is stale stops quietly. */
    @Volatile private var gen = 0
    private val sessionSockets = CopyOnWriteArrayList<Socket>()
    @Volatile private var mediaCtrlOut: OutputStream? = null
    @Volatile private var linked = false
    private val touchExec = Executors.newSingleThreadExecutor { r -> Thread(r, "dash-touch").apply { isDaemon = true } }

    /** Negotiated canvas (what the phone encodes at). Touch coordinates are in this space. */
    @Volatile var canvasW = 0
        private set
    @Volatile var canvasH = 0
        private set

    private val huid = "VDASH" + (100000 + (Math.random() * 899999).toInt())

    fun start() {
        if (running) return
        running = true
        thread(name = "dash-probe", isDaemon = true) { probeLoop() }
        registerNsd()
    }

    fun stop() {
        running = false
        try { probeServer?.close() } catch (_: IOException) {}
        probeServer = null
        unregisterNsd()
        endSession(gen, "stopped")
    }

    // ---- 1. probe ----

    private fun probeLoop() {
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(Pxc.PORT_PROBE))
            }
        } catch (e: IOException) {
            log("[PROBE] can't listen on :${Pxc.PORT_PROBE}: ${e.message}")
            return
        }
        probeServer = ss
        log("[PROBE] listening on :${Pxc.PORT_PROBE}")
        while (running) {
            val s = try { ss.accept() } catch (e: IOException) {
                if (running) log("[PROBE] accept ended: ${e.message}")
                break
            }
            thread(name = "dash-probe-conn", isDaemon = true) { handleProbe(s) }
        }
    }

    private fun handleProbe(s: Socket) {
        val from = s.inetAddress?.hostAddress
        try {
            s.soTimeout = 5_000
            // RideScreen's port scan opens and closes without sending: that's a clean EOF, not an error.
            val f = Pxc.readFrame(s.getInputStream()) ?: return
            if (f.cmd != Pxc.MDNS_RESPOND) {
                log("[PROBE] $from sent ${f.hex} (not a probe) — ignored")
                return
            }
            log("[PROBE] <- $from ${f.text}")
            Pxc.writeFrame(s.getOutputStream(), Pxc.MDNS_RESPOND_ACK, """{"status":true}""")
            log("[PROBE] -> status:true — connecting back to the phone")
            val phone = s.inetAddress as? Inet4Address ?: run {
                log("[PROBE] phone address isn't IPv4 ($from)")
                return
            }
            startSession(phone)
        } catch (e: Exception) {
            log("[PROBE] $from: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { s.close() } catch (_: IOException) {}
        }
    }

    // ---- 2–3. session ----

    @Synchronized
    private fun startSession(phone: Inet4Address) {
        endSession(gen, "phone probed again")
        val g = ++gen
        listener.onPhone(phone.hostAddress ?: "?")
        thread(name = "dash-session", isDaemon = true) { runSession(phone, g) }
    }

    private fun runSession(phone: InetAddress, g: Int) {
        var reason = "session ended"
        try {
            // The phone's servers are already listening (it binds them before probing); give it a moment
            // to close the probe socket.
            Thread.sleep(200)

            // CAR_CTRL: channel select, CLIENT_INFO, QUERY_SPEED.
            val ctrl = connect(phone, Pxc.PORT_CTRL, g)
            val ctrlQ = startCtrlReader("CAR_CTRL", ctrl, g)
            val ctrlOut = ctrl.getOutputStream()
            Pxc.writeFrame(ctrlOut, Pxc.CH_CAR_CTRL)
            expect(ctrlQ, Pxc.CH_CAR_CTRL + 1, "CAR_CTRL select")
            Pxc.writeFrame(ctrlOut, Pxc.CLIENT_INFO, clientInfo())
            log("[CTRL] -> CLIENT_INFO (touch, landscape, ${wantW}x$wantH)")
            val rly = expect(ctrlQ, Pxc.CLIENT_INFO_RLY, "CLIENT_INFO reply")
            log("[CTRL] <- phone CLIENT_INFO ${rly.text.take(160)}…")
            Pxc.writeFrame(ctrlOut, Pxc.QUERY_SPEED, """{"usbSpeed":0,"wifiSpeed":0}""")
            expect(ctrlQ, Pxc.QUERY_SPEED + 1, "QUERY_SPEED", required = false)

            // CAR_DATA: channel select, CHECK_SN (the reader acks the phone's CHECK_SN_RESULT).
            val data = connect(phone, Pxc.PORT_CTRL, g)
            val dataQ = startCtrlReader("CAR_DATA", data, g)
            val dataOut = data.getOutputStream()
            Pxc.writeFrame(dataOut, Pxc.CH_CAR_DATA)
            expect(dataQ, Pxc.CH_CAR_DATA + 1, "CAR_DATA select")
            Pxc.writeFrame(dataOut, Pxc.CHECK_SN, """{"client_set":"easy_conn","sn":"$huid"}""")
            expect(dataQ, Pxc.CHECK_SN + 1, "CHECK_SN", required = false)
            startHeartbeat(ctrlOut, g)
            startHeartbeat(dataOut, g)

            // Media control: negotiate the canvas and start the encoder.
            val mc = connect(phone, Pxc.PORT_MEDIA_CTRL, g)
            val mcIn = BufferedInputStream(mc.getInputStream())
            val mcOut = mc.getOutputStream()
            Pxc.writeReq(mcOut, Pxc.REQ_CONFIG_CAPTURE, configCapture())
            log("[MEDIA] -> CONFIG_CAPTURE ${wantW}x$wantH")
            val cfg = expectReq(mcIn, Pxc.REQ_CONFIG_CAPTURE + 1)
            val cb = ByteBuffer.wrap(cfg.body).order(ByteOrder.LITTLE_ENDIAN)
            if (cfg.body.size < 8) error("short CONFIG_CAPTURE reply (${cfg.body.size}b)")
            canvasW = cb.getShort(4).toInt() and 0xFFFF
            canvasH = cb.getShort(6).toInt() and 0xFFFF
            log("[MEDIA] <- phone will encode ${canvasW}x$canvasH (encoder=${cb.getInt(0)})")
            Pxc.writeReq(mcOut, Pxc.REQ_GET_VERSION)
            expectReq(mcIn, Pxc.REQ_GET_VERSION + 1)
            Pxc.writeReq(mcOut, Pxc.REQ_DATA_START)
            expectReq(mcIn, Pxc.REQ_DATA_START + 1)
            log("[MEDIA] <- DATA_START ok")
            mediaCtrlOut = mcOut
            startMediaHeartbeat(mc, mcIn, mcOut, g)

            linked = true
            listener.onLinked(canvasW, canvasH)
            reason = pullFrames(phone, g)
        } catch (e: InterruptedException) {
            reason = "stopped"
        } catch (e: Exception) {
            reason = "${e.javaClass.simpleName}: ${e.message}"
            if (g == gen) log("[SESSION] $reason")
        } finally {
            endSession(g, reason)
        }
    }

    /** :10920 lock-step pull. Returns why it stopped. */
    private fun pullFrames(phone: InetAddress, g: Int): String {
        val d = connect(phone, Pxc.PORT_MEDIA_DATA, g)
        d.soTimeout = 3_000 // the phone waits up to 1.5 s for a frame, then sends nothing
        val input = BufferedInputStream(d.getInputStream(), 256 * 1024)
        val out = d.getOutputStream()
        val sizeBuf = ByteArray(4)
        var frames = 0L
        var idle = 0
        var lastLog = System.currentTimeMillis()
        while (running && g == gen) {
            Pxc.writeReq(out, Pxc.REQ_DATA_NEXT)
            val got = Pxc.readFullyOrIdle(input, sizeBuf)
            if (got == null) {
                // The phone had no frame within its 1.5 s window and sent nothing: ask again.
                idle++
                if (idle == 1 || idle % 10 == 0) log("[DATA] no frame from the phone yet (×$idle)")
                if (idle >= 20) return "no video for ~60 s"
                continue
            }
            if (!got) return "phone closed the video socket"
            idle = 0
            val size = ByteBuffer.wrap(sizeBuf).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
            if (size <= 0 || size > 8 shl 20) return "bad frame size $size"
            val au = ByteArray(size)
            if (!Pxc.readFully(input, au)) return "phone closed mid-frame"
            frames++
            listener.onFrame(au)
            val now = System.currentTimeMillis()
            if (frames <= 3 || now - lastLog >= 10_000) {
                lastLog = now
                log("[DATA] frame #$frames (${size}b)")
            }
        }
        return "stopped"
    }

    private fun endSession(g: Int, reason: String) {
        if (g != gen) return
        val wasLinked = linked
        linked = false
        mediaCtrlOut = null
        val socks = sessionSockets.toList()
        sessionSockets.clear()
        socks.forEach { try { it.close() } catch (_: IOException) {} }
        if (socks.isNotEmpty() || wasLinked) {
            log("[SESSION] closed: $reason")
            listener.onUnlinked(reason)
        }
    }

    private fun connect(host: InetAddress, port: Int, g: Int): Socket {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 4_000)
        if (g != gen) {
            // A newer probe started another session while we were connecting.
            try { s.close() } catch (_: IOException) {}
            throw InterruptedException("superseded")
        }
        sessionSockets += s
        log("[NET] connected to phone :$port")
        return s
    }

    /**
     * Reads a control socket forever: acks the phone's heartbeats and its own notifications (every
     * phone-originated PXC message has an even cmd and wants `cmd+1` back — CHECK_SN_RESULT 0x201c0,
     * APPSTATUS 0x200x0…), and queues everything else for [expect].
     */
    private fun startCtrlReader(label: String, s: Socket, g: Int): LinkedBlockingQueue<Pxc.Frame> {
        val q = LinkedBlockingQueue<Pxc.Frame>(256)
        thread(name = "dash-$label", isDaemon = true) {
            try {
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                while (running && g == gen) {
                    val f = Pxc.readFrame(input) ?: break
                    when {
                        f.cmd == Pxc.HEARTBEAT -> Pxc.writeFrame(out, Pxc.HEARTBEAT_ACK)
                        f.cmd == Pxc.HEARTBEAT_ACK -> Unit
                        (f.cmd and 1) == 0 -> {
                            log("[$label] <- phone ${f.hex} ${f.text.take(100)} → ack")
                            Pxc.writeFrame(out, f.cmd + 1)
                        }
                        else -> q.offer(f)
                    }
                }
            } catch (e: Exception) {
                if (running && g == gen) log("[$label] read ended: ${e.message}")
            }
            if (running && g == gen) endSession(g, "$label closed by the phone")
        }
        return q
    }

    private fun expect(q: LinkedBlockingQueue<Pxc.Frame>, cmd: Int, what: String, required: Boolean = true): Pxc.Frame {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val f = if (left > 0) q.poll(left, TimeUnit.MILLISECONDS) else null
            if (f == null) {
                if (required) error("no $what reply from the phone")
                log("[CTRL] (no $what reply — continuing)")
                return Pxc.Frame(cmd, ByteArray(0))
            }
            if (f.cmd == cmd) return f
            log("[CTRL] <- ${f.hex} ${f.text.take(100)} (while waiting for $what)")
        }
    }

    private fun expectReq(input: InputStream, type: Int): Pxc.Req {
        while (true) {
            val r = Pxc.readReq(input) ?: error("phone closed media control (waiting for $type)")
            if (r.cmdType == type) return r
            log("[MEDIA] <- cmdType=${r.cmdType} len=${r.body.size} (waiting for $type)")
        }
    }

    private fun startHeartbeat(out: OutputStream, g: Int) = thread(name = "dash-hb", isDaemon = true) {
        try {
            while (running && g == gen) {
                Thread.sleep(2_000)
                Pxc.writeFrame(out, Pxc.HEARTBEAT)
            }
        } catch (_: Exception) {
        }
    }

    /** Media-control keep-alive (64 → 65) plus a reader that drains the phone's replies. */
    private fun startMediaHeartbeat(s: Socket, input: InputStream, out: OutputStream, g: Int) {
        thread(name = "dash-mc-read", isDaemon = true) {
            try {
                while (running && g == gen) {
                    val r = Pxc.readReq(input) ?: break
                    if (r.cmdType != Pxc.REQ_HEARTBEAT + 1) log("[MEDIA] <- cmdType=${r.cmdType} len=${r.body.size}")
                }
            } catch (_: Exception) {
            }
            if (running && g == gen && !s.isClosed) endSession(g, "phone closed media control")
        }
        thread(name = "dash-mc-hb", isDaemon = true) {
            try {
                while (running && g == gen) {
                    Thread.sleep(2_000)
                    Pxc.writeReq(out, Pxc.REQ_HEARTBEAT)
                }
            } catch (_: Exception) {
            }
        }
    }

    // ---- 4. touch ----

    /**
     * One touch event in canvas pixels. [action]: 2 = DOWN, 3 = MOVE, 1 = UP (the dash codes the phone
     * decodes in EasyConnProber.handleTouch). Sent off the UI thread, dropped when not linked.
     */
    fun sendTouch(action: Int, pointerId: Int, x: Int, y: Int) {
        val out = mediaCtrlOut ?: return
        if (!linked) return
        val body = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
        body.putShort(0, action.toShort())
        body.putShort(2, x.coerceIn(0, maxOf(0, canvasW - 1)).toShort())
        body.putShort(4, y.coerceIn(0, maxOf(0, canvasH - 1)).toShort())
        body.putShort(6, pointerId.toShort())
        body.putInt(8, (System.currentTimeMillis() and 0x7FFFFFFF).toInt())
        val bytes = body.array()
        touchExec.execute {
            try { Pxc.writeReq(out, Pxc.REQ_TOUCH, bytes) } catch (e: Exception) {
                log("[TOUCH] send failed: ${e.message}")
            }
        }
    }

    // ---- what we tell the phone ----

    /**
     * Scores as the phone's "CFDL26 / MotoPlay Landscape" profile (BikeProfile.kt): CFDL26
     * version_name + sdk 1.x + package com.cfmoto.easyconnect + sock-server auth + supportFunction 128
     * + landscape-adaptive, with a touchscreen. The HUID must not start with CARB / 6KWV / CRCP, which
     * route to other profiles.
     */
    private fun clientInfo(): String = JSONObject().apply {
        put("HUID", huid)
        put("HUName", "VirtualDash-${huid.takeLast(4)}")
        put("channel", "37426")
        put("flavor", 65540)
        put("sdkVersion", "1.1.2")
        put("version_name", "CFDL26.virtual.1")
        put("package_name", "com.cfmoto.easyconnect")
        put("pxcVersion", "1.0.2")
        put("enableSockServerAuth", true)
        put("supportFunction", 128)
        put("supportLandscapeAdaptive", true)
        put("supportScreenTouch", true)
        put("supportScreenMirroring", true)
        put("screenType", 1)
        put("productType", 3)
        put("mirrorMode", 1)
        put("dpi", 160)
        put("socketTimeoutPeriodWifi", 9)
    }.toString()

    /** REQ_RV_CONFIG_CAPTURE body (docs/01 §4); the phone reads width, height, fps, encoder, ext. */
    private fun configCapture(): ByteArray {
        val b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0, wantW.toShort())
        b.putShort(2, wantH.toShort())
        b.putInt(4, 30)          // fps
        b.putInt(8, 2)           // H.264
        b.putInt(12, 2)          // supported codec: H.264
        b.putShort(16, 1)        // min quality
        b.putShort(18, 100)      // max quality
        b.putInt(20, 4_000_000)  // bitrate
        b.put(25, 1)             // touch mode
        return b.array()
    }

    // ---- mDNS: lets the phone find us even when its gateway guess is wrong (phone-hotspot mode) ----

    private fun registerNsd() {
        try {
            val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            val info = NsdServiceInfo().apply {
                serviceName = "VirtualDash-${huid.takeLast(4)}"
                serviceType = "_EasyConn._tcp"
                port = Pxc.PORT_PROBE
                setAttribute("huid", huid)
                setAttribute("huname", "VirtualDash")
                setAttribute("port", Pxc.PORT_PROBE.toString())
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) = log("[NSD] advertised ${i.serviceName}")
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) = log("[NSD] advertise failed ($code)")
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            }
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = mgr
            nsdListener = l
        } catch (e: Exception) {
            log("[NSD] unavailable: ${e.message}")
        }
    }

    private fun unregisterNsd() {
        val l = nsdListener ?: return
        try { nsd?.unregisterService(l) } catch (_: Exception) {}
        nsdListener = null
    }
}
