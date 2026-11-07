package hu.abris.varosnezo

data class LatLng(val lat: Double, val lng: Double)

/** Látnivaló; routeMeters = hol van az útvonal mentén (sorrend és időzítés alapja). */
data class Poi(
    val id: Long,
    val title: String,
    val text: String,
    val pos: LatLng,
    val routeMeters: Double,
    val views: Int = 0, // a cikk olvasottsága az elmúlt 30 napban
)

data class TransitStep(val line: String, val from: String, val to: String, val stops: Int)

data class RoutePlan(
    val path: List<LatLng>,              // teljes útvonal (gyalog + jármű), követéshez
    val transitPaths: List<List<LatLng>>, // csak a járműves szakaszok, látnivaló-kereséshez
    val steps: List<TransitStep>,
    val summary: String,
)
