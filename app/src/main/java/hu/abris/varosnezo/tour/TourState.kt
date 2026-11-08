package hu.abris.varosnezo.tour

import hu.abris.varosnezo.LatLng
import hu.abris.varosnezo.Poi
import hu.abris.varosnezo.RoutePlan
import kotlinx.coroutines.flow.MutableStateFlow

/** Közös állapot a UI és a háttérszolgáltatás között. */
object TourState {
    const val BUS = "bus"
    const val WALK = "walk"          // szabad séta
    const val WALK_ROUTE = "walkroute" // gyalogos útvonal célig

    @Volatile var lang: String = "hu"
    /** Szabad séta: ennyire megközelítve szólal meg (m), csúszkával állítható. */
    @Volatile var walkRadiusM: Int = 50
    val mode = MutableStateFlow(BUS)
    val plan = MutableStateFlow<RoutePlan?>(null)
    val pois = MutableStateFlow<List<Poi>>(emptyList())
    val running = MutableStateFlow(false)
    val nowPlaying = MutableStateFlow<String?>(null)
    val played = MutableStateFlow<Set<Long>>(emptySet())
    val progressM = MutableStateFlow(0.0)
    val me = MutableStateFlow<LatLng?>(null)
    val message = MutableStateFlow<String?>(null)
}
