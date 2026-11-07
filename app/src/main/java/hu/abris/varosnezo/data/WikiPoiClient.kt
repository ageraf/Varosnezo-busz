package hu.abris.varosnezo.data

import hu.abris.varosnezo.LatLng
import hu.abris.varosnezo.Poi
import hu.abris.varosnezo.RoutePlan
import hu.abris.varosnezo.geo.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

/**
 * Látnivalók a Wikipedia geosearch-ből a járműves szakaszok mentén.
 * Fontosság közelítése: cikkhossz. Ritkítás: minGapM-en belül csak a legfontosabb marad,
 * hogy a felolvasások ne torlódjanak.
 */
class WikiPoiClient(private val lang: String) {

    private val api = "https://$lang.wikipedia.org/w/api.php"
    private val skip = Regex("metróállomás|megállóhely|vasútállomás|villamosmegálló|metro station|railway station|tram stop")

    private data class Cand(val id: Long, val title: String, val pos: LatLng, val along: Double)

    suspend fun poisAlong(
        plan: RoutePlan,
        corridorM: Double = 200.0,
        minGapM: Double = 250.0,
    ): List<Poi> = withContext(Dispatchers.IO) {
        val cum = Geo.cumulative(plan.path)

        // 1) Geosearch 400 m-enként, 350 m sugárral -> a 200 m-es sáv lefedve
        val found = LinkedHashMap<Long, Pair<String, LatLng>>()
        for (seg in plan.transitPaths) for (p in Geo.sample(seg, 400.0)) {
            val url = "$api?action=query&list=geosearch&format=json&formatversion=2" +
                "&gscoord=${p.lat}${Http.enc("|")}${p.lng}&gsradius=350&gslimit=30"
            val arr = JSONObject(Http.get(url)).getJSONObject("query").getJSONArray("geosearch")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                found.putIfAbsent(o.getLong("pageid"), o.getString("title") to LatLng(o.getDouble("lat"), o.getDouble("lon")))
            }
        }

        // 2) Csak a sávon belüliek, megállók/állomások nélkül
        val cands = found.mapNotNull { (id, v) ->
            if (skip.containsMatchIn(v.first.lowercase())) return@mapNotNull null
            val pr = Geo.project(v.second, plan.path, cum)
            if (pr.offM <= corridorM) Cand(id, v.first, v.second, pr.alongM) else null
        }

        // 3) Rövid kivonat + cikkhossz, 20-as csomagokban
        val texts = HashMap<Long, Pair<String, Int>>()
        for (chunk in cands.map { it.id }.chunked(20)) {
            val url = "$api?action=query&prop=${Http.enc("extracts|info")}&format=json&formatversion=2" +
                "&exintro=1&explaintext=1&exsentences=3&exlimit=20" +
                "&pageids=${Http.enc(chunk.joinToString("|"))}"
            val pages = JSONObject(Http.get(url)).getJSONObject("query").getJSONArray("pages")
            for (i in 0 until pages.length()) {
                val pg = pages.getJSONObject(i)
                val ex = pg.optString("extract").trim()
                if (ex.length > 40) texts[pg.getLong("pageid")] = ex to pg.optInt("length")
            }
        }

        // 4) Ritkítás fontosság szerint, majd sorrend az útvonal mentén
        val kept = mutableListOf<Pair<Poi, Int>>()
        cands.filter { it.id in texts }
            .sortedByDescending { texts[it.id]!!.second }
            .forEach { c ->
                if (kept.none { abs(it.first.routeMeters - c.along) < minGapM }) {
                    kept += Poi(c.id, c.title, texts[c.id]!!.first, c.pos, c.along) to texts[c.id]!!.second
                }
            }
        kept.map { it.first }.sortedBy { it.routeMeters }
    }
}
