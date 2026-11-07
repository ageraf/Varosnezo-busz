package hu.abris.varosnezo

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import hu.abris.varosnezo.data.PoiTranslator
import hu.abris.varosnezo.data.TransitousClient
import hu.abris.varosnezo.data.WikiPoiClient
import hu.abris.varosnezo.tour.TourService
import hu.abris.varosnezo.tour.TourState
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

    var city by rememberSaveable { mutableStateOf("") }
    var line by rememberSaveable { mutableStateOf("") }
    var voice by rememberSaveable { mutableStateOf(TourState.lang) } // felolvasás nyelve
    var source by rememberSaveable { mutableStateOf("en") }         // Wikipedia nyelve

    var dirs by remember { mutableStateOf<List<TransitousClient.Direction>>(emptyList()) }
    var dirIdx by remember { mutableIntStateOf(0) }
    var boardIdx by remember { mutableIntStateOf(0) }
    var alightIdx by remember { mutableIntStateOf(0) }

    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingSim by remember { mutableStateOf(false) }

    val plan by TourState.plan.collectAsStateWithLifecycle()
    val pois by TourState.pois.collectAsStateWithLifecycle()
    val running by TourState.running.collectAsStateWithLifecycle()
    val nowPlaying by TourState.nowPlaying.collectAsStateWithLifecycle()
    val played by TourState.played.collectAsStateWithLifecycle()
    val progress by TourState.progressM.collectAsStateWithLifecycle()
    val message by TourState.message.collectAsStateWithLifecycle()

    val dir = dirs.getOrNull(dirIdx)

    fun selectDirection(i: Int) {
        dirIdx = i
        boardIdx = 0
        alightIdx = (dirs.getOrNull(i)?.stops?.size ?: 1) - 1
        TourState.plan.value = null
        TourState.pois.value = emptyList()
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        val ok = res[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) TourService.start(ctx, pendingSim)
        else status = "Helymeghatározási engedély nélkül nem indítható."
    }

    fun startTour(sim: Boolean) {
        pendingSim = sim
        TourState.message.value = null
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
            dirs = client.directions(city, line, voice)
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
            TourState.lang = voice
            TourState.pois.value = emptyList()
            val p = client.plan(d, boardIdx, alightIdx)
            TourState.plan.value = p
            status = "Látnivalók keresése…"
            var list = WikiPoiClient(source).poisAlong(p)
            if (source != voice) {
                status = "Fordítás… (első alkalommal letölti a nyelvi modellt)"
                list = PoiTranslator.translate(list, source, voice)
            }
            TourState.pois.value = list
            status = null
        } catch (e: Exception) {
            status = "Hiba: ${e.message}"
        } finally {
            loading = false
        }
    }

    Scaffold { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Városnéző Busz", style = MaterialTheme.typography.headlineSmall)
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
                            TourState.plan.value = null
                            TourState.pois.value = emptyList()
                        }
                        Picker(
                            "Leszállás",
                            d.stops.drop(boardIdx + 1).mapIndexed { i, s -> "${boardIdx + i + 2}. ${s.name}" },
                            alightIdx - boardIdx - 1, enabled = !running,
                        ) {
                            alightIdx = boardIdx + 1 + it
                            TourState.plan.value = null
                            TourState.pois.value = emptyList()
                        }
                        LangRow("Látnivalók", source) { source = it }
                        LangRow("Felolvasás", voice) { voice = it }
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

            plan?.let { p ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(p.summary, style = MaterialTheme.typography.titleMedium)
                        p.steps.forEach { s -> Text("${s.line}: ${s.from} → ${s.to}") }
                        Text("${pois.size} látnivaló az útvonalon")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!running) {
                                Button(onClick = { startTour(false) }, enabled = pois.isNotEmpty()) { Text("Indítás") }
                                OutlinedButton(onClick = { startTour(true) }, enabled = pois.isNotEmpty()) { Text("Szimuláció") }
                            } else {
                                Button(onClick = { TourService.stop(ctx) }) { Text("Leállítás") }
                            }
                        }
                        if (running) {
                            Text(String.format(Locale.ROOT, "Megtett út: %.1f km", progress / 1000))
                            nowPlaying?.let { Text("🔊 $it", style = MaterialTheme.typography.titleSmall) }
                        }
                        HorizontalDivider()
                    }
                }
            }

            items(pois, key = { it.id }) { poi ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        (if (poi.id in played) "✓ " else "") + poi.title,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        String.format(Locale.ROOT, "%.1f km · ", poi.routeMeters / 1000) + poi.text.take(100) + "…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
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
private fun LangRow(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(96.dp))
        listOf("hu" to "Magyar", "en" to "English").forEach { (code, name) ->
            if (value == code) Button(onClick = {}) { Text(name) }
            else OutlinedButton(onClick = { onChange(code) }) { Text(name) }
        }
    }
}
