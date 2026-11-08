package hu.abris.varosnezo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import hu.abris.varosnezo.data.TransitousClient
import hu.abris.varosnezo.data.WikiPoiClient
import hu.abris.varosnezo.tour.TourService
import hu.abris.varosnezo.tour.TourState
import hu.abris.varosnezo.ui.TourMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** Egy helymező állapota: beírt szöveg, találatok, kiválasztott találat. */
private class PlaceState {
    var text by mutableStateOf("")
    var hits by mutableStateOf<List<TransitousClient.PlaceHit>>(emptyList())
    var picked by mutableStateOf<TransitousClient.PlaceHit?>(null)
    var open by mutableStateOf(false)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { App() } }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { TransitousClient() }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val city = remember { PlaceState() }
    var line by rememberSaveable { mutableStateOf("") }
    var lang by rememberSaveable { mutableStateOf(TourState.lang) }
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var walkRadius by remember {
        mutableIntStateOf(prefs.getInt("walkRadius", 50).also { TourState.walkRadiusM = it })
    }

    var dirs by remember { mutableStateOf<List<TransitousClient.Direction>>(emptyList()) }
    var dirIdx by remember { mutableIntStateOf(0) }
    var boardIdx by remember { mutableIntStateOf(0) }
    var alightIdx by remember { mutableIntStateOf(0) }

    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val walkTo = remember { PlaceState() }
    val walkFrom = remember { PlaceState() }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var expanded by remember { mutableStateOf(setOf<Long>()) }

    val mode by TourState.mode.collectAsStateWithLifecycle()
    val plan by TourState.plan.collectAsStateWithLifecycle()
    val pois by TourState.pois.collectAsStateWithLifecycle()
    val running by TourState.running.collectAsStateWithLifecycle()
    val nowPlaying by TourState.nowPlaying.collectAsStateWithLifecycle()
    val played by TourState.played.collectAsStateWithLifecycle()
    val progress by TourState.progressM.collectAsStateWithLifecycle()
    val me by TourState.me.collectAsStateWithLifecycle()
    val message by TourState.message.collectAsStateWithLifecycle()

    val dir = dirs.getOrNull(dirIdx)

    fun clearPlan() {
        if (running) return
        TourState.plan.value = null
        TourState.pois.value = emptyList()
    }

    fun selectDirection(i: Int) {
        dirIdx = i
        boardIdx = 0
        alightIdx = (dirs.getOrNull(i)?.stops?.size ?: 1) - 1
        clearPlan()
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        val ok = res[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) pendingAction?.invoke()
        else status = "Helymeghatározási engedély nélkül nem működik."
        pendingAction = null
    }

    fun withLocationPermission(action: () -> Unit) {
        pendingAction = action
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())
    }

    fun startTour(mode: String) {
        TourState.lang = lang
        TourState.message.value = null
        status = null
        withLocationPermission { TourService.start(ctx, mode) }
    }

    fun searchPlace(st: PlaceState) = scope.launch {
        if (st.text.isBlank()) return@launch
        loading = true
        try {
            status = null
            st.hits = client.searchPlaces(st.text, me, lang)
            if (st.hits.isEmpty()) status = "Nincs találat: ${st.text}"
            if (st.hits.size == 1) st.picked = st.hits.first() else st.open = st.hits.isNotEmpty()
        } catch (e: Exception) {
            status = "Hiba: ${e.message}"
        } finally {
            loading = false
        }
    }

    /** A kiválasztott találat, vagy ha nincs, a legjobb találat (és ki is írjuk, mit választottunk). */
    suspend fun resolve(st: PlaceState, near: LatLng?): TransitousClient.PlaceHit {
        st.picked?.let { return it }
        val hit = client.searchPlaces(st.text, near, lang).firstOrNull() ?: error("Nem található: ${st.text}")
        st.picked = hit
        return hit
    }

    fun planWalk() = withLocationPermission {
        scope.launch {
            loading = true
            try {
                TourState.lang = lang
                TourState.mode.value = TourState.WALK_ROUTE
                TourState.plan.value = null
                TourState.pois.value = emptyList()
                status = "Helyzet meghatározása…"
                val here = currentLocation(ctx)
                val start = if (walkFrom.text.isBlank()) {
                    here ?: error("Nem sikerült meghatározni a helyzetedet.")
                } else {
                    resolve(walkFrom, here).pos
                }
                val dest = resolve(walkTo, start)
                status = "Gyalogos útvonal tervezése…"
                val p = client.walkRoute(start, dest, lang)
                TourState.plan.value = p
                status = "Látnivalók keresése…"
                TourState.pois.value = WikiPoiClient(lang).poisAlong(p, corridorM = 120.0, minGapM = 100.0)
                status = null
            } catch (e: Exception) {
                status = "Hiba: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    fun loadDirections() = scope.launch {
        loading = true
        try {
            status = "Járat keresése…"
            dirs = emptyList()
            dirs = client.directions(resolve(city, me), line, lang)
            selectDirection(0)
            status = null
        } catch (e: Exception) {
            status = "Hiba: ${e.message}"
        } finally {
            loading = false
        }
    }

    fun loadSights() = scope.launch {
        val d = dir ?: return@launch
        loading = true
        try {
            TourState.lang = lang
            TourState.mode.value = TourState.BUS
            TourState.pois.value = emptyList()
            val p = client.plan(d, boardIdx, alightIdx)
            TourState.plan.value = p
            status = "Látnivalók keresése…"
            TourState.pois.value = WikiPoiClient(lang).poisAlong(p)
            status = null
        } catch (e: Exception) {
            status = "Hiba: ${e.message}"
        } finally {
            loading = false
        }
    }

    val toggle: (Long) -> Unit = { id -> expanded = if (id in expanded) expanded - id else expanded + id }

    Scaffold { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                listOf("Busz", "Séta", "Térkép").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlaceField("Város", "Róma", city, onEdit = { dirs = emptyList() }) { searchPlace(city) }
                            OutlinedTextField(
                                line, { line = it; dirs = emptyList() }, Modifier.fillMaxWidth(),
                                label = { Text("Járat") }, placeholder = { Text("105, 2-es villamos") }, singleLine = true,
                            )
                            Button(
                                onClick = { loadDirections() },
                                enabled = !loading && !running && city.text.isNotBlank() && line.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Járat betöltése") }

                            dir?.let { d ->
                                Picker("Irány", dirs.map { it.label }, dirIdx, enabled = !running) { selectDirection(it) }
                                Picker(
                                    "Felszállás",
                                    d.stops.dropLast(1).mapIndexed { i, s -> "${i + 1}. ${s.name}" },
                                    boardIdx, enabled = !running,
                                ) {
                                    boardIdx = it
                                    if (alightIdx <= it) alightIdx = d.stops.size - 1
                                    clearPlan()
                                }
                                Picker(
                                    "Leszállás",
                                    d.stops.drop(boardIdx + 1).mapIndexed { i, s -> "${boardIdx + i + 2}. ${s.name}" },
                                    alightIdx - boardIdx - 1, enabled = !running,
                                ) {
                                    alightIdx = boardIdx + 1 + it
                                    clearPlan()
                                }
                                LangRow(lang) { lang = it; clearPlan() }
                                Button(
                                    onClick = { loadSights() },
                                    enabled = !loading && !running,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Látnivalók keresése") }
                            }
                            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            (status ?: message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    if (mode == TourState.BUS) plan?.let { p ->
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(p.summary, style = MaterialTheme.typography.titleMedium)
                                p.steps.forEach { s -> Text("${s.line}: ${s.from} → ${s.to}") }
                                Text("${pois.size} látnivaló az útvonalon")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!running) {
                                        Button(onClick = { startTour(TourService.MODE_BUS) }, enabled = pois.isNotEmpty()) { Text("Indítás") }
                                        OutlinedButton(onClick = { startTour(TourService.MODE_SIM) }, enabled = pois.isNotEmpty()) { Text("Szimuláció") }
                                    } else {
                                        Button(onClick = { TourService.stop(ctx) }) { Text("Leállítás") }
                                    }
                                }
                                if (running) {
                                    Text(String.format(Locale.ROOT, "Megtett út: %.1f km", progress / 1000))
                                    NowPlaying(nowPlaying)
                                }
                                HorizontalDivider()
                            }
                        }
                        poiItems(pois, played, expanded, showKm = true, onToggle = toggle)
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }

                1 -> LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LangRow(lang) { lang = it; clearPlan() }

                            Text("Úti céllal", style = MaterialTheme.typography.titleMedium)
                            PlaceField("Hová", "Pantheon", walkTo) { searchPlace(walkTo) }
                            PlaceField("Honnan", "üresen: ahol most vagy", walkFrom) { searchPlace(walkFrom) }
                            Button(
                                onClick = { planWalk() },
                                enabled = !loading && !running && walkTo.text.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Gyalogos útvonal és látnivalók") }
                            if (mode == TourState.WALK_ROUTE) plan?.let { p ->
                                Text(p.summary + " → " + p.steps.first().to, style = MaterialTheme.typography.titleSmall)
                                Text("${pois.size} látnivaló az útvonalon")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!running) {
                                        Button(onClick = { startTour(TourService.MODE_WALK_ROUTE) }, enabled = pois.isNotEmpty()) { Text("Indítás") }
                                        OutlinedButton(onClick = { startTour(TourService.MODE_WALK_SIM) }, enabled = pois.isNotEmpty()) { Text("Szimuláció") }
                                    }
                                }
                            }

                            HorizontalDivider()
                            Text("Szabad séta", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Útvonal nélkül: amerre mész, a közeli látnivalókat felolvassa, amikor $walkRadius m-re odaérsz.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text("Megszólalási távolság: $walkRadius m", style = MaterialTheme.typography.labelLarge)
                            Slider(
                                value = walkRadius.toFloat(),
                                onValueChange = {
                                    walkRadius = (Math.round(it / 10f) * 10).coerceIn(20, 300)
                                    TourState.walkRadiusM = walkRadius
                                },
                                onValueChangeFinished = { prefs.edit().putInt("walkRadius", walkRadius).apply() },
                                valueRange = 20f..300f,
                                steps = 27,
                            )
                            if (!running) {
                                OutlinedButton(onClick = { startTour(TourService.MODE_WALK) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Szabad séta indítása")
                                }
                            }

                            if (running) {
                                Button(onClick = { TourService.stop(ctx) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        when (mode) {
                                            TourState.BUS -> "Busztúra leállítása"
                                            else -> "Séta leállítása"
                                        }
                                    )
                                }
                                when (mode) {
                                    TourState.WALK -> Text("${pois.size} látnivaló a környéken, ${played.size} felolvasva")
                                    TourState.WALK_ROUTE -> Text(String.format(Locale.ROOT, "Megtett út: %.1f km", progress / 1000))
                                }
                                NowPlaying(nowPlaying)
                            }
                            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            (status ?: message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            HorizontalDivider()
                        }
                    }
                    if (mode == TourState.WALK || mode == TourState.WALK_ROUTE) {
                        poiItems(pois, played, expanded, showKm = mode == TourState.WALK_ROUTE, onToggle = toggle)
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }

                else -> Box(Modifier.fillMaxSize().clipToBounds()) {
                    TourMap(
                        path = plan?.path,
                        pois = pois,
                        played = played,
                        me = me,
                        follow = running,
                        modifier = Modifier.fillMaxSize(),
                    )
                    nowPlaying?.let {
                        Surface(
                            Modifier.align(Alignment.BottomCenter).padding(12.dp),
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 4.dp,
                            shadowElevation = 4.dp,
                        ) { Text("🔊 $it", Modifier.padding(12.dp)) }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission") // csak engedélykérés után hívjuk
private suspend fun currentLocation(ctx: Context): LatLng? = suspendCancellableCoroutine { cont ->
    LocationServices.getFusedLocationProviderClient(ctx)
        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
        .addOnSuccessListener { l -> cont.resume(l?.let { LatLng(it.latitude, it.longitude) }) }
        .addOnFailureListener { cont.resume(null) }
}

/** Látnivalók listája; koppintásra kinyílik a teljes leírás. */
private fun LazyListScope.poiItems(
    pois: List<Poi>,
    played: Set<Long>,
    expanded: Set<Long>,
    showKm: Boolean,
    onToggle: (Long) -> Unit,
) {
    items(pois, key = { it.id }) { poi ->
        val open = poi.id in expanded
        Column(
            Modifier.fillMaxWidth().clickable { onToggle(poi.id) }.padding(vertical = 6.dp)
        ) {
            Text(
                (if (poi.id in played) "✓ " else "") + poi.title,
                style = MaterialTheme.typography.titleSmall,
            )
            val prefix = if (showKm) String.format(Locale.ROOT, "%.1f km · ", poi.routeMeters / 1000) else ""
            if (open) {
                Text(prefix + poi.text, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    prefix + poi.text, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Helymező: beírás, „Keres” gomb, találatok legördülő listában (megye/járás), a választás kiírva. */
@Composable
private fun PlaceField(
    label: String,
    placeholder: String,
    st: PlaceState,
    onEdit: () -> Unit = {},
    onSearch: () -> Unit,
) {
    Column {
        Box {
            OutlinedTextField(
                value = st.text,
                onValueChange = {
                    st.text = it
                    st.picked = null
                    st.hits = emptyList()
                    onEdit()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                singleLine = true,
                trailingIcon = { TextButton(onClick = onSearch, enabled = st.text.isNotBlank()) { Text("Keres") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
            DropdownMenu(expanded = st.open, onDismissRequest = { st.open = false }) {
                st.hits.forEach { h ->
                    DropdownMenuItem(
                        text = { Text(h.label) },
                        onClick = {
                            st.picked = h
                            st.text = h.name
                            st.open = false
                            onEdit()
                        },
                    )
                }
            }
        }
        st.picked?.let {
            Text("✓ ${it.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun NowPlaying(title: String?) {
    title?.let { Text("🔊 $it", style = MaterialTheme.typography.titleSmall) }
}

/** Legördülő választó (gomb + menü). */
@Composable
private fun Picker(label: String, options: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(
                onClick = { open = true },
                enabled = enabled && options.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    options.getOrNull(selected) ?: "–",
                    Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(" ▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(i); open = false })
                }
            }
        }
    }
}

@Composable
private fun LangRow(value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Nyelv", Modifier.width(64.dp))
        listOf("hu" to "Magyar", "en" to "English").forEach { (code, name) ->
            if (value == code) Button(onClick = {}) { Text(name) }
            else OutlinedButton(onClick = { onChange(code) }) { Text(name) }
        }
    }
}
