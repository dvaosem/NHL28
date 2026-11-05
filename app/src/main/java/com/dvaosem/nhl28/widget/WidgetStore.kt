package com.dvaosem.nhl28.widget

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cache for the tipping widget. The web app pushes today's games (and its theme colors) here;
 * tips tapped on the widget are queued locally and sent to Firestore by the web app on its next open.
 */
object WidgetStore {
    private const val PREFS = "nhl28_widget"

    data class Game(
        val id: String, val dayKey: String, val away: String, val home: String,
        val awayName: String, val homeName: String, val kickoff: Long, val status: String, val pick: String
    ) {
        val open: Boolean get() = status != "final" && status != "void" && status != "live" && kickoff > System.currentTimeMillis()
    }

    class Theme(val bg: Int, val card: Int, val text: Int, val dim: Int, val acc: Int)

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun color(o: JSONObject?, key: String, def: Int): Int =
        try { Color.parseColor(o?.optString(key).orEmpty()) } catch (_: Exception) { def }

    private fun pendingArr(c: Context): JSONArray =
        try { JSONArray(sp(c).getString("pending", "[]")) } catch (_: Exception) { JSONArray() }

    private fun pendingPicks(c: Context): Map<String, String> {
        val a = pendingArr(c)
        val m = HashMap<String, String>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            m[o.optString("gameId")] = o.optString("pick")
        }
        return m
    }

    @Synchronized
    fun saveData(c: Context, json: String) {
        val o = JSONObject(json)
        val pend = pendingPicks(c)
        val gs = o.optJSONArray("games") ?: JSONArray()
        // a pick queued on the widget wins over a stale one pushed by the page
        for (i in 0 until gs.length()) {
            val g = gs.getJSONObject(i)
            pend[g.optString("id")]?.let { g.put("pick", it) }
        }
        val e = sp(c).edit().putString("data", o.toString())
        o.optJSONObject("theme")?.let { e.putString("theme", it.toString()) }
        e.apply()
    }

    /** Generic payloads (results, board, chat): {theme:{...}, ...}. */
    @Synchronized
    fun putKind(c: Context, kind: String, json: String) {
        val o = JSONObject(json)
        val e = sp(c).edit().putString("kind_$kind", json)
        o.optJSONObject("theme")?.let { e.putString("theme", it.toString()) }
        e.apply()
    }

    fun kind(c: Context, kind: String): JSONObject? =
        try { sp(c).getString("kind_$kind", null)?.let { JSONObject(it) } } catch (_: Exception) { null }

    private fun data(c: Context): JSONObject? =
        try { sp(c).getString("data", null)?.let { JSONObject(it) } } catch (_: Exception) { null }

    fun games(c: Context): List<Game> {
        val gs = data(c)?.optJSONArray("games") ?: return emptyList()
        val out = ArrayList<Game>()
        for (i in 0 until gs.length()) {
            val g = gs.getJSONObject(i)
            out.add(
                Game(
                    g.optString("id"), g.optString("dayKey"), g.optString("away"), g.optString("home"),
                    g.optString("awayName").ifBlank { g.optString("away") },
                    g.optString("homeName").ifBlank { g.optString("home") },
                    g.optLong("kickoff"), g.optString("status"), g.optString("pick")
                )
            )
        }
        return out
    }

    fun label(c: Context): String = data(c)?.optString("label").orEmpty()

    fun theme(c: Context): Theme {
        val t = try { sp(c).getString("theme", null)?.let { JSONObject(it) } } catch (_: Exception) { null }
            ?: data(c)?.optJSONObject("theme")
        return Theme(
            color(t, "bg", Color.rgb(38, 37, 34)),
            color(t, "card", Color.rgb(48, 46, 43)),
            color(t, "text", Color.rgb(239, 235, 227)),
            color(t, "dim", Color.rgb(170, 163, 153)),
            color(t, "acc", Color.rgb(233, 228, 218))
        )
    }

    fun pendingIds(c: Context): Set<String> = pendingPicks(c).keys

    fun pendingJson(c: Context): String = pendingArr(c).toString()

    @Synchronized
    fun clearPending(c: Context, idsJson: String) {
        val ids = HashSet<String>()
        try {
            val a = JSONArray(idsJson)
            for (i in 0 until a.length()) ids.add(a.getString(i))
        } catch (_: Exception) { return }
        val a = pendingArr(c)
        val n = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optString("gameId") !in ids) n.put(o)
        }
        sp(c).edit().putString("pending", n.toString()).apply()
    }

    /** Returns true when the tip was accepted (game still open). */
    @Synchronized
    fun queueTip(c: Context, gameId: String, pick: String): Boolean {
        val o = data(c) ?: return false
        val gs = o.optJSONArray("games") ?: return false
        for (i in 0 until gs.length()) {
            val g = gs.getJSONObject(i)
            if (g.optString("id") != gameId) continue
            val status = g.optString("status")
            if (status == "final" || status == "void" || status == "live" || g.optLong("kickoff") <= System.currentTimeMillis()) return false
            g.put("pick", pick)
            val old = pendingArr(c)
            val n = JSONArray()
            for (j in 0 until old.length()) {
                val p = old.getJSONObject(j)
                if (p.optString("gameId") != gameId) n.put(p)
            }
            n.put(
                JSONObject().put("gameId", gameId).put("pick", pick)
                    .put("dayKey", g.optString("dayKey")).put("kickoff", g.optLong("kickoff"))
            )
            sp(c).edit().putString("data", o.toString()).putString("pending", n.toString()).apply()
            return true
        }
        return false
    }
}
