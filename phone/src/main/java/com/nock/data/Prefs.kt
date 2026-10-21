package com.nock.data

import android.content.Context

/** Your settings, in one place (phone). */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("nock", Context.MODE_PRIVATE)

    /** Who's shooting. Every arrow is tagged with this person's id; Home and review show only theirs. */
    var active: String
        get() {
            val a = p.getString("active", FIRST) ?: FIRST
            // A guest is for one visit: after a few hours Nock goes back to the first person by itself.
            if (a == GUEST && System.currentTimeMillis() - p.getLong("guest_since", 0L) > GUEST_MS) { p.edit().putString("active", FIRST).apply(); return FIRST }
            return a
        }
        set(v) { p.edit().putString("active", v).apply(); if (v == GUEST) p.edit().putLong("guest_since", System.currentTimeMillis()).apply() }

    /** Everyone who shoots on this phone, as (id, name). The first person is whoever set the phone up. */
    val people: List<Pair<String, String>> get() {
        val a = org.json.JSONArray(p.getString("people", null) ?: return listOf(FIRST to (p.getString("name", "") ?: "")))
        return (0 until a.length()).map { a.getJSONArray(it).let { e -> e.getString(0) to e.getString(1) } }
    }

    private fun savePeople(list: List<Pair<String, String>>) =
        p.edit().putString("people", org.json.JSONArray(list.map { org.json.JSONArray(listOf(it.first, it.second)) }).toString()).apply()

    /** Add a person (kept for good) and, unless [makeActive] is false, make them the one shooting. */
    fun addPerson(name: String, makeActive: Boolean = true): String {
        val list = people
        val id = "p" + ((list.maxOfOrNull { it.first.drop(1).toIntOrNull() ?: 0 } ?: 0) + 1)
        savePeople(list + (id to name)); if (makeActive) active = id
        return id
    }

    /** Take someone off the list (not the first person). Their arrows stay on the phone, just hidden. */
    fun removePerson(id: String) {
        if (id == FIRST) return
        savePeople(people.filter { it.first != id })
        if (active == id) active = FIRST
    }

    /** The active person's name. */
    var name: String
        get() = if (active == GUEST) "Guest" else people.firstOrNull { it.first == active }?.second ?: ""
        set(v) { if (active != GUEST) savePeople(people.map { if (it.first == active) it.first to v else it }) }
    // Per person (the first person keeps the original keys); someone new starts from the first person's values.
    private fun mine(key: String) = if (active == FIRST) key else "${key}_$active"
    var arrowsPerEnd: Int get() = p.getInt(mine("arrows_per_end"), p.getInt("arrows_per_end", 5)); set(v) = p.edit().putInt(mine("arrows_per_end"), v).apply()
    var distanceYd: Int get() = p.getInt(mine("distance_yd"), p.getInt("distance_yd", 10)); set(v) = p.edit().putInt(mine("distance_yd"), v).apply()
    var metric: Boolean get() = p.getBoolean("metric", false); set(v) = p.edit().putBoolean("metric", v).apply()
    var buzz: Int get() = p.getInt("buzz", 2); set(v) = p.edit().putInt("buzz", v).apply()
    var quality: Int get() = p.getInt("quality", 1); set(v) = p.edit().putInt("quality", v).apply()

    companion object {
        const val FIRST = "p0" // arrows saved before profiles existed belong to the first person
        /** Someone just trying it: their arrows teach the finder and get their own report, never anyone's stats. */
        const val GUEST = "guest"
        private const val GUEST_MS = 4 * 60 * 60 * 1000L

        /** A length for display, in the units you picked. */
        fun len(inches: Float, metric: Boolean) = if (metric) "%.1f mm".format(inches * 25.4f) else "%.2f\"".format(inches)
    }
}
