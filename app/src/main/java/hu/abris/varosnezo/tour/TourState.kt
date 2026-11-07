package hu.abris.varosnezo.tour

import hu.abris.varosnezo.Poi
import hu.abris.varosnezo.RoutePlan
import kotlinx.coroutines.flow.MutableStateFlow

/** Közös állapot a UI és a háttérszolgáltatás között. */
object TourState {
    @Volatile var lang: String = "hu"
    val plan = MutableStateFlow<RoutePlan?>(null)
    val pois = MutableStateFlow<List<Poi>>(emptyList())
    val running = MutableStateFlow(false)
    val nowPlaying = MutableStateFlow<String?>(null)
    val played = MutableStateFlow<Set<Long>>(emptySet())
    val progressM = MutableStateFlow(0.0)
    val message = MutableStateFlow<String?>(null)
}
