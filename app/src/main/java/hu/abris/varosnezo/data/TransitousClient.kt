package hu.abris.varosnezo.data

import hu.abris.varosnezo.BuildConfig
import hu.abris.varosnezo.LatLng
import hu.abris.varosnezo.RoutePlan
import hu.abris.varosnezo.TransitStep
import hu.abris.varosnezo.geo.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Város + járatszám + felszállási és leszállási megálló -> a járat tényleges útvonala a két megálló között.
 * Ingyenes MOTIS API (alapból Transitous; saját szerverhez csak a ROUTING_URL-t kell átírni).
 */
class TransitousClient(private val baseUrl: String = BuildConfig.ROUTING_URL) {

    private data class Stop(val name: String, val id: String, val pos: LatLng)
    private data class Dep(val tripId: String, val headsign: String, val stopId: String, val stopName: String)

    suspend fun lineRoute(city: String, line: String, board: String, alight: String, lang: String): RoutePlan =
        withContext(Dispatchers.IO) {
            val lineKey = normLine(line) ?: error("Adj meg járatszámot, pl. 40 vagy 2-es villamos.")
            val c = geocodeCity(city, lang)
            val cands = stopCandidates(board, c, lang).take(4)
            if (cands.isEmpty()) error("Nem található megálló: $board ($city)")

            // Most induló járatok; ha nincs (pl. éjjel), 12 órával későbbi indulások
            var deps = emptyList<Dep>()
            val later = Instant.now().plus(12, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toString()
            loop@ for (t in listOf(null, later)) for (s in cands) {
                deps = departures(s, lineKey, t)
                if (deps.isNotEmpty()) break@loop
            }
            if (deps.isEmpty()) error("A(z) $lineKey járat nem indul innen: ${cands.first().name}")

            // Minden irányt és megállóoszlopot kipróbálunk (körjáratnál ugyanaz a megálló többször is
            // szerepelhet); a legrövidebb utat választjuk, ami eléri a leszállási megállót.
            val boardKey = norm(board)
            val alightKey = norm(alight)
            val nextStops = LinkedHashSet<String>()
            val legCache = HashMap<String, JSONObject>()
            var best: Triple<JSONObject, List<Stop>, Pair<Int, Int>>? = null
            val tries = deps.distinctBy { it.headsign to it.stopId }
                .sortedByDescending { nameMatches(boardKey, it.stopName) }
                .take(8)
            for (d in tries) {
                val leg = legCache.getOrPut(d.tripId) { tripLeg(d.tripId) }
                val stops = legStops(leg)
                val bis = stops.indices.filter { stops[it].id == d.stopId }
                    .ifEmpty { stops.indices.filter { norm(stops[it].name) == norm(d.stopName) } }
                for (bi in bis) {
                    val ai = (bi + 1 until stops.size).firstOrNull { nameMatches(alightKey, stops[it].name) }
                    if (ai == null) {
                        stops.drop(bi + 1).forEach { nextStops += it.name }
                        continue
                    }
                    val cur = best
                    if (cur == null || ai - bi < cur.third.second - cur.third.first) best = Triple(leg, stops, bi to ai)
                }
            }
            best?.let { return@withContext buildPlan(it.first, it.second, it.third.first, it.third.second) }
            error("Nem találom a(z) „$alight” megállót a(z) $lineKey útvonalán. Megállók: " +
                nextStops.take(30).joinToString(", "))
        }

    private fun buildPlan(leg: JSONObject, stops: List<Stop>, bi: Int, ai: Int): RoutePlan {
        val geom = leg.getJSONObject("legGeometry")
        val full = Geo.decodePolyline(geom.getString("points"), geom.optInt("precision", 6))
        val cum = Geo.cumulative(full)
        var seg = 0
        var d0 = 0.0
        var d1 = 0.0
        for (i in 0..ai) { // megállónként előre haladva vetítünk (körjáratnál is jó)
            val pr = Geo.projectFirst(stops[i].pos, full, cum, seg)
            seg = pr.segIndex
            if (i == bi) d0 = pr.alongM
            if (i == ai) d1 = pr.alongM
        }
        if (d1 <= d0) error("Nem sikerült a járat útvonalát kivágni a két megálló között.")
        val path = Geo.slice(full, cum, d0, d1)
        val step = TransitStep(
            line = leg.optString("displayName").ifBlank { leg.optString("routeShortName") },
            from = stops[bi].name,
            to = stops[ai].name,
            stops = ai - bi,
        )
        val summary = String.format(Locale.ROOT, "%.1f km, %d megálló", (d1 - d0) / 1000, ai - bi)
        return RoutePlan(path, listOf(path), listOf(step), summary)
    }

    private fun get(path: String, vararg params: Pair<String, Any>): String =
        Http.get(baseUrl + path + "?" + params.joinToString("&") { (k, v) -> "$k=${Http.enc(v.toString())}" })

    private fun geocodeCity(city: String, lang: String): LatLng {
        val arr = JSONArray(get("/api/v1/geocode", "text" to city, "language" to lang))
        if (arr.length() == 0) error("Nem található város: $city")
        val m = arr.getJSONObject(0)
        return LatLng(m.getDouble("lat"), m.getDouble("lon"))
    }

    private fun stopCandidates(name: String, city: LatLng, lang: String): List<Stop> {
        val arr = JSONArray(
            get(
                "/api/v1/geocode",
                "text" to name, "type" to "STOP", "language" to lang,
                "place" to "${city.lat},${city.lng}", "placeBias" to 5, "numResults" to 10,
            )
        )
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Stop(it.optString("name"), it.optString("id"), LatLng(it.getDouble("lat"), it.getDouble("lon"))) }
            .filter { it.id.isNotEmpty() && Geo.dist(it.pos, city) < 40_000 }
    }

    private fun departures(stop: Stop, lineKey: String, time: String?): List<Dep> {
        val params = mutableListOf<Pair<String, Any>>("stopId" to stop.id, "radius" to 250, "n" to 150)
        if (time != null) params += "time" to time
        val st = JSONObject(get("/api/v5/stoptimes", *params.toTypedArray())).optJSONArray("stopTimes")
            ?: return emptyList()
        return (0 until st.length()).map { st.getJSONObject(it) }
            .filter { lineMatches(lineKey, it.optString("routeShortName"), it.optString("displayName")) }
            .map {
                val pl = it.getJSONObject("place")
                Dep(it.getString("tripId"), it.optString("headsign"), pl.optString("stopId"), pl.optString("name"))
            }
    }

    private fun tripLeg(tripId: String): JSONObject =
        JSONObject(get("/api/v5/trip", "tripId" to tripId)).getJSONArray("legs").getJSONObject(0)

    private fun legStops(leg: JSONObject): List<Stop> {
        fun s(o: JSONObject) = Stop(o.optString("name"), o.optString("stopId"), LatLng(o.getDouble("lat"), o.getDouble("lon")))
        val inter = leg.optJSONArray("intermediateStops")
        return buildList {
            add(s(leg.getJSONObject("from")))
            if (inter != null) for (i in 0 until inter.length()) add(s(inter.getJSONObject(i)))
            add(s(leg.getJSONObject("to")))
        }
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

        /** Ékezet, kis-nagybetű és írásjelek nélkül: „Fővám tér” == „FOVAM TER”. */
        fun norm(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]"), "")

        fun nameMatches(key: String, stopName: String): Boolean {
            val n = norm(stopName)
            if (key.length < 3 || n.isEmpty()) return false
            return n.contains(key) || (n.length >= 3 && key.contains(n))
        }
    }
}
