// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package io.github.karaboon.ridescreendash

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.security.SecureRandom

/**
 * The dash's Wi‑Fi as a Wi‑Fi Direct group, like the CFMoto 1000 MT-X's `DIRECT-CF-…` network.
 * Unlike a LocalOnlyHotspot (random name and password on every start), the name and password stay
 * the same, so the RideScreen app that scanned our QR once finds us again on its own: auto-connect
 * on launch, "wait for the bike", Bluetooth trigger.
 *
 *  - Android 10+: we choose the name ([DashIdentity.networkName], unique per device) and a password generated once and kept.
 *  - Android 8–9: Android picks both; it normally reuses its persistent group, so they rarely
 *    change. [DashActivity] notices when they do and asks for one rescan.
 *
 * The phone joins it as an ordinary network (QR `action=1`) and this device is the gateway,
 * 192.168.49.1.
 */
class DashP2p(private val context: Context, private val log: (String) -> Unit) {
    private val mgr = context.applicationContext.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false

    @SuppressLint("MissingPermission") // DashActivity asks for location / nearby-devices first.
    fun start(onReady: (ssid: String, password: String) -> Unit, onFailed: (String) -> Unit) {
        val m = mgr ?: return onFailed("this device has no Wi‑Fi Direct")
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        if (wm != null && !wm.isWifiEnabled) return onFailed("Wi‑Fi is off — turn it on")
        val ch = m.initialize(context.applicationContext, Looper.getMainLooper()) {
            log("[P2P] Wi‑Fi Direct channel lost")
        } ?: return onFailed("Wi‑Fi Direct unavailable")
        channel = ch
        active = true
        // Drop any group left from a previous run (or another app), then create ours.
        m.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = create(m, ch, onReady, onFailed)
            override fun onFailure(reason: Int) = create(m, ch, onReady, onFailed)
        })
    }

    @SuppressLint("MissingPermission")
    private fun create(
        m: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        onReady: (String, String) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        if (!active) return
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("[P2P] group created — reading its name and password")
                readGroup(m, ch, 0, onReady, onFailed)
            }

            override fun onFailure(reason: Int) {
                log("[P2P] createGroup failed: ${reasonText(reason)}")
                onFailed("Wi‑Fi Direct: ${reasonText(reason)}")
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val cfg = WifiP2pConfig.Builder()
                .setNetworkName(DashIdentity.networkName(context))
                .setPassphrase(passphrase())
                // 2.4 GHz: every phone can join it, and it reaches further on a bike.
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                .build()
            m.createGroup(ch, cfg, listener)
        } else {
            m.createGroup(ch, listener)
        }
    }

    /** The group's details can lag the createGroup success by a moment: retry a few times. */
    @SuppressLint("MissingPermission")
    private fun readGroup(
        m: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        attempt: Int,
        onReady: (String, String) -> Unit,
        onFailed: (String) -> Unit,
    ) {
        if (!active) return
        m.requestGroupInfo(ch) { g: WifiP2pGroup? ->
            val ssid = g?.networkName.orEmpty()
            val pwd = g?.passphrase.orEmpty()
            when {
                ssid.isNotEmpty() && pwd.isNotEmpty() -> {
                    log("[P2P] dash Wi‑Fi up: \"$ssid\" (owner=${g?.isGroupOwner})")
                    onReady(ssid, pwd)
                }
                attempt < 10 -> main.postDelayed({ readGroup(m, ch, attempt + 1, onReady, onFailed) }, 500)
                else -> onFailed("Wi‑Fi Direct group has no name/password")
            }
        }
    }

    fun stop() {
        active = false
        val ch = channel ?: return
        channel = null
        try {
            mgr?.removeGroup(ch, null)
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= 27) try { ch.close() } catch (_: Exception) {}
    }

    /** 16 random letters/digits, made once and kept: the password must not change between runs. */
    private fun passphrase(): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_PASS, null)?.let { return it }
        val abc = "abcdefghijkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        val pass = (1..16).map { abc[r.nextInt(abc.length)] }.joinToString("")
        p.edit().putString(KEY_PASS, pass).apply()
        return pass
    }

    private fun reasonText(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "not supported"
        WifiP2pManager.BUSY -> "busy (turn Wi‑Fi off and on, or reboot)"
        WifiP2pManager.ERROR -> "error (is Location on?)"
        else -> "reason $reason"
    }

    companion object {
        private const val PREFS = "dash_wifi"
        private const val KEY_PASS = "p2p_pass"
    }
}
