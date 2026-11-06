// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package io.github.karaboon.ridescreendash

import android.content.Context
import java.security.SecureRandom

/**
 * This device's identity as a dash, made once and kept: 6 hex digits. It goes into the Wi‑Fi Direct
 * name, the QR's `sn` (RideScreen keys the screen's settings by it — BikeMemory.virtualDashId) and
 * the HUID, so two virtual dashes never share a network name or a settings profile.
 */
object DashIdentity {
    private const val PREFS = "dash_identity"
    private const val KEY_ID = "id"

    fun id(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY_ID, null)?.let { return it }
        val id = "%06X".format(SecureRandom().nextInt(0x1000000))
        p.edit().putString(KEY_ID, id).apply()
        return id
    }

    /** What RideScreen shows in its garage for this screen. */
    fun displayName(context: Context) = "RideScreen Dash ${id(context)}"

    /** Wi‑Fi Direct group name (Android requires the DIRECT-xy prefix). */
    fun networkName(context: Context) = "DIRECT-RS-Dash-${id(context)}"
}
