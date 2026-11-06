// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package io.github.karaboon.ridescreendash

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
 *
 * Wi‑Fi modes ([WifiMode]), switchable with the mode button and remembered:
 *  - DIRECT (default): a Wi‑Fi Direct group with a fixed name/password ([DashP2p]) — scan once,
 *    then RideScreen reconnects by itself.
 *  - HOTSPOT: a LocalOnlyHotspot ([DashHotspot]) — new name/password every start, rescan each time.
 *  - PHONE: this device joins the phone's hotspot; RideScreen finds us by subnet scan / mDNS.
 */
class DashActivity : Activity() {

    enum class WifiMode { DIRECT, HOTSPOT, PHONE }

    private lateinit var video: SurfaceView
    private lateinit var overlay: LinearLayout
    private lateinit var qrView: ImageView
    private lateinit var statusView: TextView
    private lateinit var modeButton: Button
    private lateinit var bootButton: Button
    private lateinit var logView: TextView

    private lateinit var link: DashLink
    private lateinit var hotspot: DashHotspot
    private lateinit var p2p: DashP2p

    private val playerLock = Any()
    private var player: H264Player? = null
    @Volatile private var surface: Surface? = null

    private var mode = WifiMode.DIRECT
    private var wantW = 0
    private var wantH = 0

    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        computeCanvas()
        hotspot = DashHotspot(this, ::log)
        p2p = DashP2p(this, ::log)
        link = DashLink(applicationContext, wantW, wantH, ::log, linkListener)
        link.start()
        log("[DASH] canvas ${wantW}x$wantH · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MODEL}")
        mode = runCatching { WifiMode.valueOf(prefs().getString(KEY_MODE, null) ?: "") }.getOrDefault(WifiMode.DIRECT)
        startWifi()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        refreshBootButton()
    }

    override fun onDestroy() {
        link.stop()
        hotspot.stop()
        p2p.stop()
        releasePlayer()
        super.onDestroy()
    }

    // ---- Wi‑Fi + QR ----

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun wifiPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION

    private fun nextMode() {
        hotspot.stop()
        p2p.stop()
        mode = WifiMode.values()[(mode.ordinal + 1) % WifiMode.values().size]
        prefs().edit().putString(KEY_MODE, mode.name).apply()
        startWifi()
    }

    private fun startWifi() {
        modeButton.text = getString(R.string.btn_mode, getString(modeLabel(mode)))
        qrView.setImageDrawable(null)
        if (mode == WifiMode.PHONE) return usePhoneHotspot()
        if (checkSelfPermission(wifiPermission()) != PackageManager.PERMISSION_GRANTED) {
            statusView.setText(R.string.perm_needed)
            requestPermissions(arrayOf(wifiPermission()), REQ_PERM)
            return
        }
        statusView.setText(R.string.status_starting)
        val started = mode
        val onReady = { ssid: String, pwd: String ->
            if (mode == started) showWifiQr(ssid, pwd)
        }
        val onFailed = { why: String ->
            if (mode == started) {
                log("[WIFI] ${mode.name} unavailable: $why")
                statusView.text = getString(R.string.status_wifi_failed, why)
                qrView.setImageDrawable(null)
            }
        }
        when (mode) {
            WifiMode.DIRECT -> p2p.start(onReady, onFailed)
            WifiMode.HOTSPOT -> hotspot.start(onReady, onFailed)
            WifiMode.PHONE -> Unit
        }
    }

    /** QR with our Wi‑Fi (Carbit SoftAP style, action=1) + a note on whether RideScreen can auto-connect. */
    private fun showWifiQr(ssid: String, pwd: String) {
        showQr(
            "http://www.carbit.com.cn/downsdk/657/658/_sdk?modelid=$MODEL_ID&sn=VD01&action=1" +
                "&ssid=${Uri.encode(ssid)}&pwd=${Uri.encode(pwd)}&auth=wpa2-psk&name=${Uri.encode(ssid)}",
        )
        val p = prefs()
        val known = p.getString(KEY_SSID, null)
        val same = known == ssid && p.getString(KEY_PWD, null) == pwd
        p.edit().putString(KEY_SSID, ssid).putString(KEY_PWD, pwd).apply()
        val note = when {
            mode == WifiMode.HOTSPOT -> getString(R.string.note_hotspot_changes)
            known == null -> getString(R.string.note_first_scan)
            same -> getString(R.string.note_auto)
            else -> getString(R.string.note_changed)
        }
        if (known != null && !same) log("[WIFI] Wi‑Fi name/password changed since last time — rescan needed")
        statusView.text = getString(R.string.status_scan) + "\nWi‑Fi: $ssid\n" + note
    }

    /**
     * Fallback for devices that can't host Wi‑Fi: this device joins the PHONE's hotspot as a normal
     * Wi‑Fi client, and the QR is a Carbit "phone hotspot" one (`action=128`): RideScreen then scans
     * its hotspot subnet and finds us on :10930 (also advertised over mDNS).
     */
    private fun usePhoneHotspot() {
        showQr("http://www.carbit.com.cn/downsdk/657/658/_sdk?modelid=$MODEL_ID&sn=VD01&action=128&bm=02:00:00:00:00:01&name=VirtualDash")
        statusView.setText(R.string.status_phone_hotspot)
        log("[WIFI] phone-hotspot mode")
    }

    private fun modeLabel(m: WifiMode) = when (m) {
        WifiMode.DIRECT -> R.string.mode_direct
        WifiMode.HOTSPOT -> R.string.mode_hotspot
        WifiMode.PHONE -> R.string.mode_phone
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startWifi()
        else statusView.text = getString(R.string.status_wifi_failed, getString(R.string.perm_needed))
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

    // ---- open at power-on: offer this app as the device's Home screen ----

    /** The HOME entry point is an activity-alias, off until the rider asks for it. */
    private fun homeAlias() = ComponentName(this, "$packageName.HomeAlias")

    private fun homeEnabled() =
        packageManager.getComponentEnabledSetting(homeAlias()) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun toggleBoot() {
        val on = !homeEnabled()
        packageManager.setComponentEnabledSetting(
            homeAlias(),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        log(if (on) "[BOOT] Home option on — pick RideScreen จอจำลอง as Home" else "[BOOT] Home option off")
        refreshBootButton()
        if (on) {
            // Let the rider pick us as the default Home app (falls back to app settings if missing).
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun refreshBootButton() {
        if (::bootButton.isInitialized) {
            bootButton.setText(if (homeEnabled()) R.string.btn_boot_off else R.string.btn_boot_on)
        }
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
            setBackgroundColor(BG)
            setPadding(32, 32, 32, 32)
        }
        qrView = ImageView(this)
        overlay.addView(qrView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 0, 0, 0)
        }
        val title = TextView(this).apply {
            setText(R.string.app_name)
            setTextColor(GOLD)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val iconPx = (44 * resources.displayMetrics.density).toInt()
            val icon = getDrawable(R.mipmap.ic_launcher)?.apply { setBounds(0, 0, iconPx, iconPx) }
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = 16
            gravity = Gravity.CENTER_VERTICAL
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeButton = Button(this).apply { setOnClickListener { nextMode() } }
        bootButton = Button(this).apply { setOnClickListener { toggleBoot() } }
        buttons.addView(modeButton)
        buttons.addView(bootButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = 16 })
        logView = TextView(this).apply {
            setTextColor(Color.rgb(150, 170, 155))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        side.addView(title)
        side.addView(statusView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 })
        side.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 })
        side.addView(logView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 })
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
            while (logLines.size > 12) logLines.removeFirst()
            if (::logView.isInitialized) logView.text = logLines.joinToString("\n")
        }
    }

    companion object {
        private const val TAG = "RideScreenDash"
        private const val REQ_PERM = 7
        private const val MAX_W = 1280
        /** Tells RideScreen this is the virtual dash (touch, landscape): BikeProfiles.VIRTUAL_DASH_MODEL_ID. */
        private const val MODEL_ID = "RSDASH"
        private const val PREFS = "dash_ui"
        private const val KEY_MODE = "wifi_mode"
        private const val KEY_SSID = "last_ssid"
        private const val KEY_PWD = "last_pwd"
        private val BG = Color.rgb(0x16, 0x1E, 0x19)
        private val GOLD = Color.rgb(0xD1, 0xA9, 0x55)
    }
}
