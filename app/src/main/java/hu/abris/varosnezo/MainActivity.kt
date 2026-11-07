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

    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var voice by rememberSaveable { mutableStateOf(TourState.lang) } // felolvasás nyelve
    var source by rememberSaveable { mutableStateOf("en") }         // Wikipedia nyelve
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

    fun planRoute() = scope.launch {
        loading = true
        try {
            TourState.lang = voice
            TourState.pois.value = emptyList()
            status = "Útvonal tervezése…"
            val p = TransitousClient().transitRoute(from, to, voice)
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
            item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Városnéző Busz", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(from, { from = it }, Modifier.fillMaxWidth(), label = { Text("Honnan (A)") }, singleLine = true)
                OutlinedTextField(to, { to = it }, Modifier.fillMaxWidth(), label = { Text("Hová (B)") }, singleLine = true)
                LangRow("Látnivalók", source) { source = it }
                LangRow("Felolvasás", voice) { voice = it }
                Button(
                    onClick = { planRoute() },
                    enabled = !loading && !running && from.isNotBlank() && to.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Útvonal és látnivalók") }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                (status ?: message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }

            plan?.let { p ->
                item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.summary, style = MaterialTheme.typography.titleMedium)
                    p.steps.forEach { s -> Text("${s.line}: ${s.from} → ${s.to} (${s.stops} megálló)") }
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
                } }
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
