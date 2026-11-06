// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package io.github.karaboon.ridescreendash

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Full-screen virtual dash: Android Auto video from the RideScreen phone app, touch sent back, and —
 * while not linked — the pairing QR the phone scans (like a bike's MotoPlay QR screen).
 */
class DashActivity : Activity() {

    private lateinit var video: SurfaceView
    private lateinit var overlay: LinearLayout
    private lateinit var qrView: ImageView
    private lateinit var statusView: TextView
    private lateinit var modeButton: Button
    private lateinit var logView: TextView

    private lateinit var link: DashLink
    private lateinit var hotspot: DashHotspot

    private val playerLock = Any()
    private var player: H264Player? = null
    @Volatile private var surface: Surface? = null

    /** True = the dash hosts its own Wi‑Fi (LocalOnlyHotspot); false = it joined the phone's hotspot. */
    private var ownHotspot = true
    private var wantW = 0
    private var wantH = 0

    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        computeCanvas()
        hotspot = DashHotspot(this, ::log)
        link = DashLink(applicationContext, wantW, wantH, ::log, linkListener)
        link.start()
        log("[DASH] canvas ${wantW}x$wantH · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL}")
        startOwnHotspot()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onDestroy() {
        link.stop()
        hotspot.stop()
        releasePlayer()
        super.onDestroy()
    }

    // ---- Wi‑Fi + QR ----

    private fun wifiPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION

    private fun startOwnHotspot() {
        ownHotspot = true
        modeButton.setText(R.string.btn_phone_hotspot)
        if (checkSelfPermission(wifiPermission()) != PackageManager.PERMISSION_GRANTED) {
            statusView.setText(R.string.perm_needed)
            requestPermissions(arrayOf(wifiPermission()), REQ_PERM)
            return
        }
        statusView.setText(R.string.status_starting)
        qrView.setImageDrawable(null)
        hotspot.start(
            onReady = { ssid, pwd ->
                if (!ownHotspot) return@start
                showQr(
                    "http://www.carbit.com.cn/downsdk/657/658/_sdk?modelid=$MODEL_ID&sn=VD01&action=1" +
                        "&ssid=${Uri.encode(ssid)}&pwd=${Uri.encode(pwd)}&auth=wpa2-psk&name=${Uri.encode(ssid)}",
                )
                statusView.text = getString(R.string.status_scan) + "\nWi‑Fi: $ssid"
            },
            onFailed = { why ->
                if (!ownHotspot) return@start
                log("[WIFI] own hotspot unavailable: $why")
                statusView.text = getString(R.string.status_hotspot_failed, why)
                qrView.setImageDrawable(null)
            },
        )
    }

    /**
     * Fallback for devices that can't host a hotspot: this device joins the PHONE's hotspot as a
     * normal Wi‑Fi client, and the QR is a Carbit "phone hotspot" one (`action=128`): RideScreen then
     * scans its hotspot subnet and finds us on :10930 (also advertised over mDNS).
     */
    private fun usePhoneHotspot() {
        ownHotspot = false
        hotspot.stop()
        modeButton.setText(R.string.btn_own_hotspot)
        showQr("http://www.carbit.com.cn/downsdk/657/658/_sdk?modelid=$MODEL_ID&sn=VD01&action=128&bm=02:00:00:00:00:01&name=VirtualDash")
        statusView.setText(R.string.status_phone_hotspot)
        log("[WIFI] phone-hotspot mode")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startOwnHotspot()
        else statusView.text = getString(R.string.status_hotspot_failed, getString(R.string.perm_needed))
    }

    private fun showQr(text: String) {
        val size = (minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 0.55f).toInt()
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) {
            px[y * m.width + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        }
        qrView.setImageBitmap(Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888))
    }

    // ---- link → screen ----

    private val linkListener = object : DashLink.Listener {
        override fun onPhone(phone: String) = runOnUiThread {
            statusView.text = getString(R.string.status_linking, phone)
        }

        override fun onLinked(width: Int, height: Int) {
            synchronized(playerLock) { newPlayer(width, height) }
            runOnUiThread { overlay.visibility = View.GONE }
        }

        override fun onFrame(au: ByteArray) {
            synchronized(playerLock) {
                try { player?.feed(au) } catch (e: Exception) {
                    log("[VIDEO] feed failed: ${e.javaClass.simpleName}: ${e.message}")
                    player?.release()
                    player = null
                    // Start over on the next keyframe.
                    newPlayer(link.canvasW, link.canvasH)
                }
            }
        }

        override fun onUnlinked(reason: String) {
            releasePlayer()
            runOnUiThread {
                statusView.text = getString(R.string.status_lost, reason)
                overlay.visibility = View.VISIBLE
            }
        }
    }

    /** Caller holds [playerLock]. No surface (activity in background) → frames are dropped. */
    private fun newPlayer(w: Int, h: Int) {
        val s = surface ?: return
        if (w <= 0 || h <= 0) return
        player?.release()
        player = H264Player(s, w, h, ::log)
    }

    private fun releasePlayer() = synchronized(playerLock) {
        player?.release()
        player = null
    }

    // ---- touch → phone ----

    private fun onVideoTouch(v: View, ev: MotionEvent): Boolean {
        val cw = link.canvasW
        val ch = link.canvasH
        if (cw <= 0 || ch <= 0 || v.width <= 0 || v.height <= 0) return true
        fun send(action: Int, i: Int) = link.sendTouch(
            action,
            ev.getPointerId(i),
            (ev.getX(i) * cw / v.width).toInt(),
            (ev.getY(i) * ch / v.height).toInt(),
        )
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> send(2, ev.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until ev.pointerCount) send(3, i)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> send(1, ev.actionIndex)
            MotionEvent.ACTION_CANCEL -> for (i in 0 until ev.pointerCount) send(1, i)
        }
        return true
    }

    // ---- layout ----

    /**
     * Ask for (about) this screen, landscape, capped at 1280 px wide: older devices' H.264 decoders
     * often top out at 720p, and the phone re-encodes to whatever we ask anyway.
     */
    @Suppress("DEPRECATION")
    private fun computeCanvas() {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        var w = maxOf(dm.widthPixels, dm.heightPixels)
        var h = minOf(dm.widthPixels, dm.heightPixels)
        if (w > MAX_W) {
            h = h * MAX_W / w
            w = MAX_W
        }
        // Multiples of 16, as the phone rounds them anyway (& ~15).
        wantW = w and 0xFFF0
        wantH = h and 0xFFF0
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = SurfaceView(this)
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                // Back from the background mid-session: a fresh decoder that waits for the next keyframe.
                synchronized(playerLock) { if (player == null) newPlayer(link.canvasW, link.canvasH) }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                releasePlayer()
                surface = null
            }
        })
        video.setOnTouchListener { v, ev -> onVideoTouch(v, ev) }
        root.addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(16, 22, 18))
            setPadding(32, 32, 32, 32)
        }
        qrView = ImageView(this)
        overlay.addView(qrView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 0, 0, 0)
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
        }
        modeButton = Button(this).apply {
            setOnClickListener { if (ownHotspot) usePhoneHotspot() else startOwnHotspot() }
        }
        logView = TextView(this).apply {
            setTextColor(Color.rgb(150, 170, 155))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        side.addView(statusView)
        side.addView(modeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })
        side.addView(logView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })
        overlay.addView(side, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    // ---- log ----

    private fun log(msg: String) {
        Log.i(TAG, msg)
        runOnUiThread {
            logLines.addLast(msg)
            while (logLines.size > 14) logLines.removeFirst()
            if (::logView.isInitialized) logView.text = logLines.joinToString("\n")
        }
    }

    companion object {
        private const val TAG = "RideScreenDash"
        private const val REQ_PERM = 7
        private const val MAX_W = 1280
        /** CFDL26 family modelId: RideScreen's QR guess for an AP dash with it is the touch landscape profile. */
        private const val MODEL_ID = "37426"
    }
}
