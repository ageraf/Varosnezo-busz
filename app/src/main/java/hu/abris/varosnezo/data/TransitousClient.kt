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
import java.util.Locale

/**
 * Ingyenes, kulcs nélküli tömegközlekedési tervezés a MOTIS API-n keresztül
 * (alapból a Transitous közösségi szervere; saját MOTIS-ra csak a ROUTING_URL-t kell átírni).
 */
class TransitousClient(private val baseUrl: String = BuildConfig.ROUTING_URL) {

    private fun geocode(text: String, lang: String): LatLng {
        val arr = JSONArray(Http.get("$baseUrl/api/v1/geocode?text=${Http.enc(text)}&language=$lang"))
        if (arr.length() == 0) error("Nem található cím: $text")
        val m = arr.getJSONObject(0)
        return LatLng(m.getDouble("lat"), m.getDouble("lon"))
    }

    suspend fun transitRoute(origin: String, destination: String, lang: String): RoutePlan =
        withContext(Dispatchers.IO) {
            val a = geocode(origin, lang)
            val b = geocode(destination, lang)

            val url = "$baseUrl/api/v5/plan" +
                "?fromPlace=${a.lat},${a.lng}&toPlace=${b.lat},${b.lng}" +
                "&transitModes=BUS,TRAM&language=$lang"
            val its = JSONObject(Http.get(url)).optJSONArray("itineraries")
            if (its == null || its.length() == 0) error("Nincs busz/villamos útvonal A és B között.")
            val it0 = its.getJSONObject(0)

            val path = mutableListOf<LatLng>()
            val transitPaths = mutableListOf<List<LatLng>>()
            val steps = mutableListOf<TransitStep>()

            val legs = it0.getJSONArray("legs")
            for (i in 0 until legs.length()) {
                val leg = legs.getJSONObject(i)
                val geom = leg.optJSONObject("legGeometry") ?: continue
                val pts = Geo.decodePolyline(geom.getString("points"), geom.optInt("precision", 6))
                path += pts
                if (!leg.has("tripId")) continue // gyalogos szakasz
                transitPaths += pts
                steps += TransitStep(
                    line = leg.optString("displayName").ifBlank { leg.optString("routeShortName") },
                    from = leg.optJSONObject("from")?.optString("name").orEmpty(),
                    to = leg.optJSONObject("to")?.optString("name").orEmpty(),
                    stops = (leg.optJSONArray("intermediateStops")?.length() ?: 0) + 1,
                )
            }
            if (path.size < 2 || transitPaths.isEmpty()) error("Az útvonalon nincs járműves szakasz.")

            val km = Geo.cumulative(path).last() / 1000
            val min = it0.optLong("duration") / 60
            RoutePlan(path, transitPaths, steps, String.format(Locale.ROOT, "%.1f km, %d perc", km, min))
        }
}
