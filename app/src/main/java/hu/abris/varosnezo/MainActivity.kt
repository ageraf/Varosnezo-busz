package hu.abris.varosnezo

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.abris.varosnezo.data.TransitousClient
import hu.abris.varosnezo.data.WikiPoiClient
import hu.abris.varosnezo.tour.TourService
import hu.abris.varosnezo.tour.TourState
import hu.abris.varosnezo.ui.TourMap
import kotlinx.coroutines.launch
import java.util.Locale

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
    var city by rememberSaveable { mutableStateOf("") }
    var line by rememberSaveable { mutableStateOf("") }
    var lang by rememberSaveable { mutableStateOf(TourState.lang) }

    var dirs by remember { mutableStateOf<List<TransitousClient.Direction>>(emptyList()) }
    var dirIdx by remember { mutableIntStateOf(0) }
    var boardIdx by remember { mutableIntStateOf(0) }
    var alightIdx by remember { mutableIntStateOf(0) }

    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingMode by remember { mutableStateOf(TourService.MODE_BUS) }
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
        if (ok) TourService.start(ctx, pendingMode)
        else status = "Helymeghatározási engedély nélkül nem indítható."
    }

    fun startTour(mode: String) {
        pendingMode = mode
        TourState.lang = lang
        TourState.message.value = null
        status = null
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())
    }

    fun loadDirections() = scope.launch {
        loading = true
        try {
            status = "Járat keresése…"
            dirs = emptyList()
            dirs = client.directions(city, line, lang)
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
                            OutlinedTextField(
                                city, { city = it; dirs = emptyList() }, Modifier.fillMaxWidth(),
                                label = { Text("Város") }, placeholder = { Text("Róma") }, singleLine = true,
                            )
                            OutlinedTextField(
                                line, { line = it; dirs = emptyList() }, Modifier.fillMaxWidth(),
                                label = { Text("Járat") }, placeholder = { Text("105, 2-es villamos") }, singleLine = true,
                            )
                            Button(
                                onClick = { loadDirections() },
                                enabled = !loading && !running && city.isNotBlank() && line.isNotBlank(),
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
                            Text(
                                "Sétálj, amerre szeretnél: az app a környékbeli látnivalókat felolvassa, amikor kb. 50 m-re odaérsz.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            LangRow(lang) { lang = it }
                            if (!running) {
                                Button(onClick = { startTour(TourService.MODE_WALK) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Séta indítása")
                                }
                            } else {
                                Button(onClick = { TourService.stop(ctx) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (mode == TourState.WALK) "Séta leállítása" else "Busztúra leállítása")
                                }
                            }
                            (status ?: message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (running && mode == TourState.WALK) {
                                Text("${pois.size} látnivaló a környéken, ${played.size} felolvasva")
                                NowPlaying(nowPlaying)
                            }
                            HorizontalDivider()
                        }
                    }
                    if (mode == TourState.WALK) poiItems(pois, played, expanded, showKm = false, onToggle = toggle)
                    item { Spacer(Modifier.height(24.dp)) }
                }

                else -> Box(Modifier.fillMaxSize()) {
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
