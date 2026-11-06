// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package io.github.karaboon.ridescreendash

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * The dash's own Wi‑Fi, like a bike's SoftAP: an Android LocalOnlyHotspot (Android 8+). Android picks
 * the name and password itself; we put them in the QR, and the phone joins with them exactly as it
 * joins a bike (QR `action=1`). The hotspot lives as long as [reservation] is held.
 */
class DashHotspot(private val context: Context, private val log: (String) -> Unit) {
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun start(onReady: (ssid: String, password: String) -> Unit, onFailed: (String) -> Unit) {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return onFailed("no Wi‑Fi")
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    val (ssid, pwd) = credentials(r)
                    log("[WIFI] dash hotspot up: \"$ssid\"")
                    if (ssid.isEmpty() || pwd.isEmpty()) onFailed("hotspot has no name/password") else onReady(ssid, pwd)
                }

                override fun onStopped() {
                    log("[WIFI] dash hotspot stopped by the system")
                    reservation = null
                    onFailed("hotspot stopped")
                }

                override fun onFailed(reason: Int) {
                    // 1 = no channel, 2 = generic, 3 = incompatible mode (e.g. normal hotspot on), 4 = tethering off
                    log("[WIFI] LocalOnlyHotspot failed, reason=$reason")
                    onFailed("reason $reason")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            onFailed("permission: ${e.message}")
        } catch (e: IllegalStateException) {
            // Thrown when this app already holds a reservation.
            onFailed(e.message ?: "already started")
        }
    }

    fun stop() {
        try { reservation?.close() } catch (_: Exception) {}
        reservation = null
    }

    @Suppress("DEPRECATION")
    private fun credentials(r: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> =
        if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            c.ssid.orEmpty() to c.passphrase.orEmpty()
        } else {
            val c = r.wifiConfiguration
            c?.SSID.orEmpty().trim('"') to c?.preSharedKey.orEmpty().trim('"')
        }
}
