package dev.magpie.usage.domain

import dev.magpie.usage.core.model.CallRow

/**
 * Stable provider display: the local-session group gets the desktop's name,
 * anything unknown keeps its raw id, and colors come from the provider id
 * alone so they survive range switches.
 */
object Providers {

    /** Display name for a provider id. */
    fun displayName(id: String): String = when (id) {
        CallRow.UNKNOWN_PROVIDER -> "本地会话"
        else -> id
    }

    /** Chart/list palette — the visual spec's four plus ten steady pastels. */
    val palette: IntArray = intArrayOf(
        0xFFA8AFFF.toInt(), // lavender
        0xFF70D4F5.toInt(), // cyan
        0xFF69DFB8.toInt(), // mint
        0xFFF5B8A8.toInt(), // salmon
        0xFFF5DFA8.toInt(), // sand
        0xFFC0A8F5.toInt(), // violet
        0xFFA8F5E0.toInt(), // aqua
        0xFFF5A8D0.toInt(), // pink
        0xFFD0F5A8.toInt(), // lime
        0xFFF5C8A8.toInt(), // peach
        0xFFA8C8F5.toInt(), // sky
        0xFFE0A8F5.toInt(), // orchid
    )

    /** Color for a provider id, stable across sessions and ranges. */
    fun colorIndex(id: String): Int {
        var h = 1125899906842597L // FNV basis
        for (c in id) {
            h = h xor c.code.toLong()
            h *= 1099511628211L
        }
        return ((h ushr 1) % palette.size).toInt()
    }
}
