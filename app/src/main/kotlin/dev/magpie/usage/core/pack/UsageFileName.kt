package dev.magpie.usage.core.pack

/**
 * Remote file naming: <computer-id>-YYYY-MM-DD.magpie-usage.
 *
 * The computer id is 8..64 lowercase hex chars (upstream usage.validID), and
 * the day is the last 10 chars before the extension — the id itself is never
 * split at a hyphen, the tail date locates it.
 * Reference: yetone/magpie @ 6acf5ee5, internal/davsync/usage.go usageName.
 */
object UsageFileName {
    const val EXTENSION = ".magpie-usage"
    private const val DAY_LEN = 10 // YYYY-MM-DD

    data class Name(val computerId: String, val day: String)

    fun parse(fileName: String): Name? {
        if (!fileName.endsWith(EXTENSION)) return null
        val base = fileName.removeSuffix(EXTENSION)
        if (base.length < DAY_LEN + 2) return null
        val sepIndex = base.length - DAY_LEN - 1
        if (base[sepIndex] != '-') return null
        val who = base.substring(0, sepIndex)
        val day = base.substring(sepIndex + 1)
        if (!validComputer(who)) return null
        if (!validDay(day)) return null
        return Name(who, day)
    }

    fun build(computerId: String, day: String): String = "$computerId-$day$EXTENSION"

    fun validComputer(id: String): Boolean {
        if (id.length < 8 || id.length > 64) return false
        for (c in id) {
            if (!(c in '0'..'9' || c in 'a'..'f')) return false
        }
        return true
    }

    fun validDay(day: String): Boolean {
        if (day.length != DAY_LEN) return false
        if (day[4] != '-' || day[7] != '-') return false
        val y = day.substring(0, 4).toIntOrNull() ?: return false
        val m = day.substring(5, 7).toIntOrNull() ?: return false
        val d = day.substring(8, 10).toIntOrNull() ?: return false
        return y in 2000..3000 && m in 1..12 && d in 1..31
    }
}
