package com.nock.data

import org.json.JSONObject
import java.io.File
import kotlin.math.hypot

/** One arrow: where it hit relative to the spot you aimed at (inches; +right, +low), plus optional wrist motion. */
class Shot(
    val index: Int,
    val timeMs: Long,
    val rightIn: Float,
    val lowIn: Float,
    val fixed: Boolean,        // you placed it yourself in review
    val checked: Boolean,      // you fixed it or said "Looks right"
    val photo: File,           // the "after" photo
    val spotX: Float, val spotY: Float, val entryX: Float, val entryY: Float, val ppi: Float,
    val motion: Motion?,
    val round: Long = 0L,      // the round (Start press) it was shot in; 0 = before rounds existed
    val json: File = File(photo.path.replace("_after.jpg", "_hit.json")),
    val yd: Int = 0,           // how far you stood (asked at Start); 0 = not known
) {
    val distIn get() = hypot(rightIn, lowIn)

    /**
     * ESTIMATE: this arrow as if shot from [toYd], assuming the same aim angle (offsets grow in proportion to distance).
     * A simplification, not measured for you; unchanged when the distance it was shot from isn't known.
     */
    fun at(toYd: Int): Shot = if (yd <= 0 || toYd == yd) this else (toYd.toFloat() / yd).let { k ->
        Shot(index, timeMs, rightIn * k, lowIn * k, fixed, checked, photo, spotX, spotY, entryX, entryY, ppi, motion, round, json, toYd)
    }
}

/** What the watch felt around the release. Trace = aim path before release, degrees (x right, y up). */
class Motion(val holdDps: Float, val traceX: FloatArray, val traceY: FloatArray, val lastDps: Float? = null, val followDps: Float? = null)

/** Arrows shot together: a new session starts after a 30-minute gap. */
class Session(val shots: List<Shot>) {
    val start get() = shots.first().timeMs
    val count get() = shots.size
    /** Typical distance from the aim point (median): the headline "how close am I". */
    val medianIn get() = shots.map { it.distIn }.sorted().let { it[it.size / 2] }
    /** Where the group sits vs the aim point (average offset): a consistent pull shows up here. */
    val biasRight get() = shots.map { it.rightIn }.average().toFloat()
    val biasLow get() = shots.map { it.lowIn }.average().toFloat()
    /** Group size: average distance of each arrow from the group's own centre. */
    val spreadIn get() = shots.map { hypot(it.rightIn - biasRight, it.lowIn - biasLow) }.average().toFloat()
    val best get() = shots.minOf { it.distIn }
}

object ShotStore {
    private const val GAP_MS = 30 * 60 * 1000L

    /** Every shot with a hit result, oldest first. Your fix wins over the app's guess; "Not an arrow" ones are left out. */
    fun load(dir: File, who: String = Prefs.FIRST): List<Shot> =
        (dir.listFiles { f -> f.name.endsWith("_hit.json") } ?: emptyArray()).mapNotNull { f ->
            runCatching {
                val parts = f.name.split('_')
                val j = JSONObject(f.readText())
                if (j.optBoolean("ignored")) return@mapNotNull null
                if (j.optString("who", Prefs.FIRST) != who) return@mapNotNull null
                if (!j.optBoolean("found", true) && !j.has("fixed")) return@mapNotNull null // lost arrow, not placed yet
                val fixed = j.has("fixed")
                val spot = j.getJSONArray("spot")
                val entry = j.getJSONArray(if (fixed) "fixed" else "entry")
                val motion = j.optJSONObject("motion")?.let { m ->
                    val xs = m.getJSONArray("x"); val ys = m.getJSONArray("y")
                    Motion(m.getDouble("hold").toFloat(), FloatArray(xs.length()) { xs.getDouble(it).toFloat() }, FloatArray(ys.length()) { ys.getDouble(it).toFloat() },
                        m.optDouble("last").takeIf { !it.isNaN() }?.toFloat(), m.optDouble("follow").takeIf { !it.isNaN() }?.toFloat())
                }
                Shot(
                    index = parts[1].toInt(), timeMs = parts[2].toLong(),
                    rightIn = (if (fixed) j.getDouble("fixed_right_in") else j.getDouble("right_in")).toFloat(),
                    lowIn = (if (fixed) j.getDouble("fixed_low_in") else j.getDouble("low_in")).toFloat(),
                    fixed = fixed,
                    checked = fixed || j.optBoolean("confirmed"),
                    photo = File(f.path.replace("_hit.json", "_after.jpg")),
                    spotX = spot.getDouble(0).toFloat(), spotY = spot.getDouble(1).toFloat(),
                    entryX = entry.getDouble(0).toFloat(), entryY = entry.getDouble(1).toFloat(),
                    ppi = j.optDouble("ppi", 62.0).toFloat(),
                    motion = motion,
                    round = j.optLong("round", 0L),
                    yd = j.optInt("yd", 0),
                )
            }.getOrNull()
        }.sortedBy { it.timeMs }

    /** Arrows you haven't fixed, confirmed or set aside, including lost ones waiting to be placed. */
    fun toCheck(dir: File, who: String = Prefs.FIRST): Int = (dir.listFiles { f -> f.name.endsWith("_hit.json") } ?: emptyArray()).count { f ->
        runCatching { JSONObject(f.readText()) }.getOrNull()?.let {
            !it.has("fixed") && !it.optBoolean("confirmed") && !it.optBoolean("ignored") && it.optString("who", Prefs.FIRST) == who
        } == true
    }

    /**
     * The rounds of a session, oldest first: shots with the same Start press. Shots from before rounds existed are
     * grouped by a 2-minute gap (the time it takes to pull arrows).
     */
    fun rounds(s: Session): List<List<Shot>> {
        val out = ArrayList<MutableList<Shot>>()
        for (x in s.shots) {
            val cur = out.lastOrNull()
            val same = cur != null && if (x.round != 0L || cur.last().round != 0L) x.round == cur.last().round else x.timeMs - cur.last().timeMs <= 120_000
            if (same) cur!!.add(x) else out.add(mutableListOf(x))
        }
        return out
    }

    /**
     * A guest's arrows last a day: older ones are deleted for good (his rule, 2026-10-08). Nothing learned is lost: the
     * arrow finder doesn't read saved arrows on the phone. Returns how many were deleted.
     */
    fun purgeGuests(dir: File, keepMs: Long = 24 * 60 * 60 * 1000L): Int {
        val cutoff = System.currentTimeMillis() - keepMs
        return (dir.listFiles { f -> f.name.endsWith("_hit.json") } ?: emptyArray()).count { f ->
            runCatching {
                val t = f.name.split('_')[2].toLong()
                if (t >= cutoff || JSONObject(f.readText()).optString("who", Prefs.FIRST) != Prefs.GUEST) return@count false
                f.delete(); File(f.path.replace("_hit.json", "_after.jpg")).delete(); File(f.path.replace("_hit.json", "_before.jpg")).delete()
                true
            }.getOrDefault(false)
        }
    }

    /** Delete shots for good: the photos and the result (no trace left). */
    fun delete(shots: Collection<Shot>) = shots.forEach { s ->
        s.json.delete(); s.photo.delete(); File(s.photo.path.replace("_after.jpg", "_before.jpg")).delete()
    }

    /** "Not an arrow" for these shots: kept on the phone, left out of everything. */
    fun ignore(shots: Collection<Shot>) = shots.forEach { s ->
        runCatching { s.json.writeText(JSONObject(s.json.readText()).put("ignored", true).toString()) }
    }

    /** Newest session first. */
    fun sessions(shots: List<Shot>): List<Session> {
        val out = ArrayList<MutableList<Shot>>()
        for (s in shots) {
            val cur = out.lastOrNull()
            if (cur == null || s.timeMs - cur.last().timeMs > GAP_MS) out.add(mutableListOf(s)) else cur.add(s)
        }
        return out.map { Session(it) }.reversed()
    }
}
