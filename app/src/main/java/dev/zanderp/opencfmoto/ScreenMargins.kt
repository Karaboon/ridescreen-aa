// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
// Adapted from ionutradu252/open-cflink.
package dev.zanderp.opencfmoto

import android.content.Context

/**
 * Black borders held back from the edges of the dash, in dash pixels.
 *
 * Per-edge (top/bottom/left/right) so each bike's chrome (e.g. 800NK Advanced MotoPlay pull-down)
 * can be blanked without a rebuild. Edited on the dedicated Screen margins page.
 *
 * Until the rider customises, [BikeProfile.defaultMargins] for the active profile apply.
 */
object ScreenMargins {
    private const val PREF = "screen_margins"
    const val MAX = 400

    @Volatile private var userTop = 0
    @Volatile private var userBottom = 0
    @Volatile private var userLeft = 0
    @Volatile private var userRight = 0

    @Volatile var customised: Boolean = false
        private set

    val top: Int get() = if (customised) userTop else BikeProfileHolder.active.defaultMargins[0]
    val bottom: Int get() = if (customised) userBottom else BikeProfileHolder.active.defaultMargins[1]
    val left: Int get() = if (customised) userLeft else BikeProfileHolder.active.defaultMargins[2]
    val right: Int get() = if (customised) userRight else BikeProfileHolder.active.defaultMargins[3]

    val any: Boolean get() = top != 0 || bottom != 0 || left != 0 || right != 0

    /**
     * Per screen (RideScreen AA): every key is scoped to the selected garage bike/dash via [BikeScope],
     * falling back to the old global values until that screen gets its own. Call again whenever the
     * selected bike changes ([BikeMemory.select] does, through [AppSettings.applyToHolder]).
     */
    fun load(context: Context) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        fun has(k: String) = p.contains(k) || BikeScope.suffix(context)?.let { p.contains("$k#$it") } == true
        customised = wasCustomised(
            hasFlag = has("customised"),
            flag = BikeScope.getBoolean(p, context, "customised", false),
            hasEdgeKeys = has("top") || has("bottom") || has("left") || has("right"),
        )
        userTop = BikeScope.getInt(p, context, "top", 0)
        userBottom = BikeScope.getInt(p, context, "bottom", 0)
        userLeft = BikeScope.getInt(p, context, "left", 0)
        userRight = BikeScope.getInt(p, context, "right", 0)
        AaVideoBridge.pipeline?.refreshScreenMargins()
    }

    internal fun wasCustomised(hasFlag: Boolean, flag: Boolean, hasEdgeKeys: Boolean): Boolean =
        if (hasFlag) flag else hasEdgeKeys

    fun set(context: Context, t: Int, b: Int, l: Int, r: Int) {
        userTop = t.coerceIn(0, MAX)
        userBottom = b.coerceIn(0, MAX)
        userLeft = l.coerceIn(0, MAX)
        userRight = r.coerceIn(0, MAX)
        customised = true
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        BikeScope.putBoolean(p, context, "customised", true)
        BikeScope.putInt(p, context, "top", userTop)
        BikeScope.putInt(p, context, "bottom", userBottom)
        BikeScope.putInt(p, context, "left", userLeft)
        BikeScope.putInt(p, context, "right", userRight)
        AaVideoBridge.pipeline?.refreshScreenMargins()
    }

    /** Back to the active profile's defaults (not necessarily zero), for the selected screen only. */
    fun reset(context: Context) {
        customised = false
        BikeScope.putBoolean(context.getSharedPreferences(PREF, Context.MODE_PRIVATE), context, "customised", false)
        AaVideoBridge.pipeline?.refreshScreenMargins()
    }

    fun summary(): String = when {
        !any -> "None — Android Auto uses the whole dash"
        !customised -> "Top $top · bottom $bottom · left $left · right $right px (profile default)"
        else -> "Top $top · bottom $bottom · left $left · right $right px"
    }
}
