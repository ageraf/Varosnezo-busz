package hu.abris.varosnezo.tour

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import hu.abris.varosnezo.LatLng
import hu.abris.varosnezo.Poi
import hu.abris.varosnezo.data.WikiPoiClient
import hu.abris.varosnezo.geo.Geo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

/**
 * Előtérben futó szolgáltatás.
 * Busz: helyzet -> útvonalra vetítés -> a következő látnivaló felolvasása, mielőtt odaér a jármű.
 * Séta: a környék látnivalóit folyamatosan tölti, és felolvassa, amelyikhez közel érsz.
 */
class TourService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_STOP = "hu.abris.varosnezo.STOP"
        const val EXTRA_MODE = "mode"
        const val MODE_BUS = "bus"
        const val MODE_SIM = "sim"
        const val MODE_WALK = "walk"
        const val MODE_WALK_ROUTE = "walkroute"
        const val MODE_WALK_SIM = "walksim"
        private const val CHANNEL = "tour"
        private const val NOTIF_ID = 1

        // Útvonalkövetés paraméterei: [minimális előre szólás m, előre szólás mp, elhaladva még m, útvonaltól max m, szimulációs m/s]
        private val BUS_PARAMS = doubleArrayOf(120.0, 12.0, 80.0, 300.0, 8.0)
        private val WALK_PARAMS = doubleArrayOf(25.0, 15.0, 40.0, 150.0, 2.5)
        private const val WALK_TRIGGER_M = 50.0 // séta: ennyire megközelítve szólal meg
        private const val WALK_REFETCH_M = 150.0 // séta: ennyi elmozdulás után új keresés

        fun start(ctx: Context, mode: String) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, TourService::class.java).putExtra(EXTRA_MODE, mode)
            )
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TourService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var tts: TextToSpeech
    private lateinit var fused: FusedLocationProviderClient
    @Volatile private var ttsReady = false
    @Volatile private var speaking = false
    private var walk = false
    private var params = BUS_PARAMS
    private var cum = DoubleArray(0)
    private var lastSeg = 0
    private var simJob: Job? = null
    private var walkFetchCenter: LatLng? = null
    private var fetching = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val loc = r.lastLocation ?: return
            onLocation(LatLng(loc.latitude, loc.longitude), if (loc.hasSpeed()) loc.speed.toDouble() else 0.0)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(notification("Városnézés fut"))

        fused.removeLocationUpdates(callback)
        simJob?.cancel()
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_BUS
        walk = mode == MODE_WALK
        val walkRoute = mode == MODE_WALK_ROUTE || mode == MODE_WALK_SIM
        params = if (walkRoute) WALK_PARAMS else BUS_PARAMS
        TourState.mode.value = when {
            walk -> TourState.WALK
            walkRoute -> TourState.WALK_ROUTE
            else -> TourState.BUS
        }
        TourState.played.value = emptySet()
        TourState.progressM.value = 0.0
        TourState.me.value = null

        if (walk) {
            walkFetchCenter = null
            TourState.plan.value = null
            TourState.pois.value = emptyList()
        } else {
            val plan = TourState.plan.value
            if (plan == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            cum = Geo.cumulative(plan.path)
            lastSeg = 0
        }
        TourState.running.value = true

        if (mode == MODE_SIM || mode == MODE_WALK_SIM) {
            val plan = TourState.plan.value!!
            val speed = params[4]
            simJob = scope.launch {
                var d = 0.0
                while (d <= cum.last()) {
                    onLocation(Geo.pointAt(plan.path, cum, d), speed)
                    d += speed
                    delay(1000)
                }
            }
        } else {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, if (walk) 3000L else 2000L)
                .setMinUpdateDistanceMeters(5f)
                .build()
            try {
                fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
            } catch (e: SecurityException) {
                TourState.message.value = "Nincs helymeghatározási engedély."
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun onLocation(p: LatLng, speed: Double) {
        TourState.me.value = p
        if (walk) onWalk(p) else onBus(p, speed)
    }

    private fun onBus(p: LatLng, speed: Double) {
        val plan = TourState.plan.value ?: return
        // Előrefelé keresünk (hurkoló útvonalnál ne ugorjon vissza); ha nem talál, teljes keresés.
        var pr = Geo.project(p, plan.path, cum, lastSeg - 5)
        if (pr.offM > 60) {
            val full = Geo.project(p, plan.path, cum)
            if (full.offM < pr.offM) pr = full
        }
        if (pr.offM > params[3]) return
        lastSeg = pr.segIndex
        TourState.progressM.value = pr.alongM

        if (!ttsReady || speaking) return
        val lead = max(params[0], speed * params[1])
        val played = TourState.played.value
        val next = TourState.pois.value
            .filter { it.id !in played && it.routeMeters - pr.alongM in -params[2]..lead }
            .minByOrNull { it.routeMeters } ?: return
        speak(next)
    }

    private fun onWalk(p: LatLng) {
        val center = walkFetchCenter
        if (!fetching && (center == null || Geo.dist(center, p) > WALK_REFETCH_M)) {
            fetching = true
            walkFetchCenter = p
            scope.launch {
                try {
                    val found = WikiPoiClient(TourState.lang).poisNear(p, 400)
                    TourState.pois.update { old -> (old + found).distinctBy { it.id } }
                } catch (e: Exception) {
                    walkFetchCenter = center // következő helyzetnél újrapróbálja
                } finally {
                    fetching = false
                }
            }
        }
        if (!ttsReady || speaking) return
        val played = TourState.played.value
        val next = TourState.pois.value
            .filter { it.id !in played }
            .map { it to Geo.dist(it.pos, p) }
            .filter { it.second <= WALK_TRIGGER_M }
            .minByOrNull { it.second }?.first ?: return
        speak(next)
    }

    private fun speak(poi: Poi) {
        speaking = true
        TourState.played.update { it + poi.id }
        TourState.nowPlaying.value = poi.title
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(poi.title))
        tts.speak("${poi.title}. ${poi.text}", TextToSpeech.QUEUE_FLUSH, null, "poi-${poi.id}")
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            TourState.message.value = "A felolvasó (TTS) nem indult el."
            return
        }
        val res = tts.setLanguage(Locale.forLanguageTag(TourState.lang))
        if (res < TextToSpeech.LANG_AVAILABLE) {
            TourState.message.value = "Nincs telepítve ${TourState.lang} hang a TTS-hez (Beállítások > Felolvasás)."
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = done()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = done()
            override fun onError(utteranceId: String?, errorCode: Int) = done()
        })
        ttsReady = true
    }

    private fun done() {
        speaking = false
        TourState.nowPlaying.value = null
    }

    private fun startForegroundCompat(n: Notification) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Városnézés", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun notification(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, TourService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Városnéző Busz")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "Leállítás", stopPi)
            .build()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        scope.cancel()
        tts.stop()
        tts.shutdown()
        TourState.running.value = false
        TourState.nowPlaying.value = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
