package hu.abris.varosnezo.data

import android.util.JsonReader
import android.util.JsonToken
import hu.abris.varosnezo.BuildConfig
import hu.abris.varosnezo.LatLng
import hu.abris.varosnezo.RoutePlan
import hu.abris.varosnezo.TransitStep
import hu.abris.varosnezo.geo.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.util.Locale

/**
 * Város + járatszám -> a járat irányai (megállósorrend + útvonal). Ingyenes MOTIS API
 * (alapból Transitous; saját szerverhez csak a ROUTING_URL-t kell átírni).
 */
class TransitousClient(private val baseUrl: String = BuildConfig.ROUTING_URL) {

    data class StopRef(val name: String, val pos: LatLng)

    /** Egy irány: megállók sorrendben, segs[k] = útvonal a k. és k+1. megálló között. */
    data class Direction(val line: String, val stops: List<StopRef>, val segs: List<List<LatLng>>) {
        val label: String get() = "${stops.first().name} → ${stops.last().name} (${stops.size} megálló)"
    }

    suspend fun directions(city: String, line: String, lang: String): List<Direction> =
        withContext(Dispatchers.IO) {
            val key = normLine(line) ?: error("Adj meg járatszámot, pl. 40 vagy 2-es villamos.")
            val c = geocodeCity(city, lang)
            // Előbb kis területen keresünk (gyors), ha nincs benne a járat, nagyobbon
            var idxs = emptyList<Int>()
            for (d in listOf(0.01, 0.03, 0.08)) {
                idxs = routeIndexes(c, d, key)
                if (idxs.isNotEmpty()) break
            }
            if (idxs.isEmpty()) error("Nem található a(z) $key járat itt: $city")
            val dirs = idxs.take(12)
                .mapNotNull { runCatching { routeDetails(it, key, lang) }.getOrNull() }
                .filter { it.stops.size >= 2 }
                .distinctBy { d -> d.stops.joinToString("|") { it.name } }
                .sortedByDescending { it.stops.size }
            if (dirs.isEmpty()) error("Nem sikerült betölteni a(z) $key irányait.")
            dirs
        }

    /** A kiválasztott irány bi. és ai. megállója közötti út. */
    fun plan(dir: Direction, bi: Int, ai: Int): RoutePlan {
        require(bi in dir.stops.indices && ai in dir.stops.indices && ai > bi)
        val path = mutableListOf<LatLng>()
        for (k in bi until ai) path += dir.segs[k]
        val km = Geo.cumulative(path).last() / 1000
        val step = TransitStep(dir.line, dir.stops[bi].name, dir.stops[ai].name, ai - bi)
        return RoutePlan(path, listOf(path), listOf(step), String.format(Locale.ROOT, "%.1f km, %d megálló", km, ai - bi))
    }

    private fun geocodeCity(city: String, lang: String): LatLng {
        val arr = JSONArray(Http.get("$baseUrl/api/v1/geocode?text=${Http.enc(city)}&language=$lang"))
        if (arr.length() == 0) error("Nem található város: $city")
        val m = arr.getJSONObject(0)
        return LatLng(m.getDouble("lat"), m.getDouble("lon"))
    }

    /** A területen közlekedő járatminták közül a keresett járatszámúak (adatfolyamként olvasva). */
    private fun routeIndexes(c: LatLng, d: Double, key: String): List<Int> {
        val url = "$baseUrl/api/experimental/map/routes?zoom=15" +
            "&min=${Http.enc("${c.lat - d},${c.lng + d}")}&max=${Http.enc("${c.lat + d},${c.lng - d}")}"
        return Http.stream(url) { input ->
            val out = mutableListOf<Int>()
            val r = JsonReader(InputStreamReader(input, Charsets.UTF_8))
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() != "routes") {
                    r.skipValue()
                    continue
                }
                r.beginArray()
                while (r.hasNext()) {
                    var idx = -1
                    var match = false
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "routeIdx" -> idx = r.nextInt()
                            "transitRoutes" -> {
                                r.beginArray()
                                while (r.hasNext()) {
                                    r.beginObject()
                                    while (r.hasNext()) {
                                        if (r.nextName() == "shortName" && r.peek() == JsonToken.STRING) {
                                            if (lineMatches(key, r.nextString(), "")) match = true
                                        } else {
                                            r.skipValue()
                                        }
                                    }
                                    r.endObject()
                                }
                                r.endArray()
                            }
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (match && idx >= 0) out += idx
                }
                r.endArray()
            }
            r.endObject()
            out
        }
    }

    private fun routeDetails(idx: Int, key: String, lang: String): Direction {
        val j = JSONObject(Http.get("$baseUrl/api/experimental/map/route-details?routeIdx=$idx&language=$lang"))
        val segsArr = j.getJSONArray("routes").getJSONObject(0).getJSONArray("segments")
        val places = j.getJSONArray("stops")
        val polys = j.getJSONArray("polylines")
        fun place(i: Int) = places.getJSONObject(i).let {
            StopRef(it.optString("name"), LatLng(it.getDouble("lat"), it.getDouble("lon")))
        }
        // A megállósorrendet a szakaszokból rakjuk össze (egy megálló többször is szerepelhet)
        val stops = mutableListOf<StopRef>()
        val segs = mutableListOf<List<LatLng>>()
        for (i in 0 until segsArr.length()) {
            val sg = segsArr.getJSONObject(i)
            if (i == 0) stops += place(sg.getInt("from"))
            val to = place(sg.getInt("to"))
            val pl = polys.optJSONObject(sg.optInt("polyline", -1))?.optJSONObject("polyline")
            segs += if (pl != null) Geo.decodePolyline(pl.getString("points"), pl.optInt("precision", 6))
            else listOf(stops.last().pos, to.pos)
            stops += to
        }
        return Direction(key, stops, segs)
    }

    companion object {
        // "40-es busz" -> 40, "4-6-os villamos" -> 4-6, "2B" -> 2B, "N64" -> N64
        private val lineRe = Regex("[A-Z]?\\d+(?:-\\d+)?(?:[A-Z](?![A-Z]))?")

        fun normLine(s: String): String? {
            val u = s.trim().uppercase(Locale.ROOT)
            if (u.isEmpty()) return null
            return lineRe.find(u)?.value ?: u
        }

        fun lineMatches(key: String, short: String, display: String): Boolean {
            val a = short.uppercase(Locale.ROOT)
            val b = display.uppercase(Locale.ROOT)
            return a == key || b == key || b.replace(" ", "") == key
        }
    }
}
