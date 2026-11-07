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
 * Látnivalók a Wikipediából, mindig a felolvasás nyelvén írt eredeti cikkel (nincs gépi fordítás).
 * Jelöltek: az adott nyelvű + az angol Wikipedia (több helyhez kötött cikk); az angolokat a
 * nyelvközi hivatkozáson át a célnyelvű cikkre cseréljük. Ahol nincs célnyelvű cikk, kimarad.
 */
class WikiPoiClient(private val lang: String) {

    private data class Cand(val wiki: String, val pageId: Long, val title: String, val pos: LatLng)
    private data class Article(val id: Long, val title: String, val text: String, val length: Int)

    private val skip = Regex(
        "metróállomás|megállóhely|vasútállomás|villamosmegálló|metro station|railway station|tram stop|bus station"
    )

    private fun api(w: String) = "https://$w.wikipedia.org/w/api.php?format=json&formatversion=2"
    private fun wikis() = if (lang == "en") listOf("en") else listOf(lang, "en")

    /** Útvonal mentén (busz). */
    suspend fun poisAlong(plan: RoutePlan, corridorM: Double = 200.0, minGapM: Double = 250.0): List<Poi> =
        withContext(Dispatchers.IO) {
            val cum = Geo.cumulative(plan.path)
            val cands = LinkedHashMap<String, Cand>()
            for (seg in plan.transitPaths) for (p in Geo.sample(seg, 400.0)) geosearch(p, 350, cands)
            val inCorridor = cands.values.mapNotNull { c ->
                val pr = Geo.project(c.pos, plan.path, cum)
                if (pr.offM <= corridorM) c to pr.alongM else null
            }
            val arts = resolve(inCorridor.map { it.first })
            val items = inCorridor.mapNotNull { (c, along) -> arts[c]?.let { Triple(it, c.pos, along) } }
                .distinctBy { it.first.id }
            // Ritkítás fontosság (cikkhossz) szerint, hogy a felolvasások ne torlódjanak
            val kept = mutableListOf<Poi>()
            items.sortedByDescending { it.first.length }.forEach { (a, pos, along) ->
                if (kept.none { abs(it.routeMeters - along) < minGapM }) kept += Poi(a.id, a.title, a.text, pos, along)
            }
            kept.sortedBy { it.routeMeters }
        }

    /** Egy pont körül (séta). */
    suspend fun poisNear(p: LatLng, radiusM: Int = 400): List<Poi> = withContext(Dispatchers.IO) {
        val cands = LinkedHashMap<String, Cand>()
        geosearch(p, radiusM, cands)
        val arts = resolve(cands.values.toList())
        cands.values.mapNotNull { c -> arts[c]?.let { Poi(it.id, it.title, it.text, c.pos, 0.0) } }
            .distinctBy { it.id }
    }

    private fun geosearch(p: LatLng, radiusM: Int, into: MutableMap<String, Cand>) {
        for (w in wikis()) {
            val url = api(w) + "&action=query&list=geosearch&gslimit=30&gsradius=$radiusM" +
                "&gscoord=${p.lat}${Http.enc("|")}${p.lng}"
            val arr = JSONObject(Http.get(url)).optJSONObject("query")?.optJSONArray("geosearch") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val title = o.getString("title")
                if (skip.containsMatchIn(title.lowercase())) continue
                val id = o.getLong("pageid")
                into.putIfAbsent("$w:$id", Cand(w, id, title, LatLng(o.getDouble("lat"), o.getDouble("lon"))))
            }
        }
    }

    /** Jelölt -> a felolvasás nyelvén írt cikk (kivonat), ha létezik. */
    private fun resolve(cands: List<Cand>): Map<Cand, Article> {
        if (cands.isEmpty()) return emptyMap()
        val targetTitle = HashMap<Cand, String>()
        cands.filter { it.wiki == lang }.forEach { targetTitle[it] = it.title }

        // Más nyelvű jelöltek -> célnyelvű cikk címe (nyelvközi hivatkozás)
        for ((w, group) in cands.filter { it.wiki != lang }.groupBy { it.wiki }) {
            for (chunk in group.chunked(50)) {
                val url = api(w) + "&action=query&prop=langlinks&lllang=$lang&lllimit=max" +
                    "&pageids=${Http.enc(chunk.joinToString("|") { it.pageId.toString() })}"
                val pages = JSONObject(Http.get(url)).optJSONObject("query")?.optJSONArray("pages") ?: continue
                val byId = chunk.associateBy { it.pageId }
                for (i in 0 until pages.length()) {
                    val pg = pages.getJSONObject(i)
                    val ll = pg.optJSONArray("langlinks") ?: continue
                    if (ll.length() == 0) continue
                    val c = byId[pg.optLong("pageid")] ?: continue
                    targetTitle[c] = ll.getJSONObject(0).getString("title")
                }
            }
        }

        // Célnyelvű kivonatok cím szerint (átirányításokat követve)
        val articles = HashMap<String, Article>()
        val alias = HashMap<String, String>()
        for (chunk in targetTitle.values.distinct().chunked(20)) {
            val url = api(lang) + "&action=query&prop=${Http.enc("extracts|info")}&redirects=1" +
                "&exintro=1&explaintext=1&exsentences=4&exlimit=20" +
                "&titles=${Http.enc(chunk.joinToString("|"))}"
            val q = JSONObject(Http.get(url)).optJSONObject("query") ?: continue
            for (key in listOf("normalized", "redirects")) {
                val arr = q.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    alias[o.getString("from")] = o.getString("to")
                }
            }
            val pages = q.optJSONArray("pages") ?: continue
            for (i in 0 until pages.length()) {
                val pg = pages.getJSONObject(i)
                val text = pg.optString("extract").trim()
                if (text.length > 40) {
                    articles[pg.getString("title")] = Article(pg.getLong("pageid"), pg.getString("title"), text, pg.optInt("length"))
                }
            }
        }
        fun canonical(t: String): String {
            var x = t
            repeat(3) { x = alias[x] ?: return x }
            return x
        }
        return targetTitle.mapNotNull { (c, t) -> articles[canonical(t)]?.let { c to it } }.toMap()
    }
}
