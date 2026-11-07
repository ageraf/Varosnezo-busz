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
 * Előtérben futó szolgáltatás: helyzetkövetés -> útvonalra vetítés -> a következő
 * látnivaló felolvasása, mielőtt a busz odaér. Szimulációs módban GPS nélkül halad végig.
 */
class TourService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_STOP = "hu.abris.varosnezo.STOP"
        const val EXTRA_SIM = "sim"
        private const val CHANNEL = "tour"
        private const val NOTIF_ID = 1

        private const val MIN_LEAD_M = 120.0  // legalább ennyivel előtte kezdjen beszélni
        private const val LEAD_S = 12.0       // ...vagy ennyi másodpercnyi út előtte
        private const val PASSED_M = 80.0     // ennyivel elhaladva még felolvasható
        private const val OFF_ROUTE_M = 300.0 // ennél messzebb nem vagyunk az útvonalon
        private const val SIM_SPEED = 8.0     // m/s, szimuláció (~29 km/h)

        fun start(ctx: Context, simulate: Boolean) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, TourService::class.java).putExtra(EXTRA_SIM, simulate)
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
    private var cum = DoubleArray(0)
    private var lastSeg = 0
    private var simJob: Job? = null

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
        val plan = TourState.plan.value
        if (plan == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Újraindításkor tiszta lap
        fused.removeLocationUpdates(callback)
        simJob?.cancel()
        cum = Geo.cumulative(plan.path)
        lastSeg = 0
        TourState.played.value = emptySet()
        TourState.progressM.value = 0.0
        TourState.running.value = true

        if (intent?.getBooleanExtra(EXTRA_SIM, false) == true) {
            simJob = scope.launch {
                var d = 0.0
                while (d <= cum.last()) {
                    onLocation(Geo.pointAt(plan.path, cum, d), SIM_SPEED)
                    d += SIM_SPEED
                    delay(1000)
                }
            }
        } else {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
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
        val plan = TourState.plan.value ?: return

        // Előrefelé keresünk (hurkoló útvonalnál ne ugorjon vissza); ha nem talál, teljes keresés.
        var pr = Geo.project(p, plan.path, cum, lastSeg - 5)
        if (pr.offM > 60) {
            val full = Geo.project(p, plan.path, cum)
            if (full.offM < pr.offM) pr = full
        }
        if (pr.offM > OFF_ROUTE_M) return
        lastSeg = pr.segIndex
        TourState.progressM.value = pr.alongM

        if (!ttsReady || speaking) return
        val lead = max(MIN_LEAD_M, speed * LEAD_S)
        val played = TourState.played.value
        val next = TourState.pois.value
            .filter { it.id !in played && it.routeMeters - pr.alongM in -PASSED_M..lead }
            .minByOrNull { it.routeMeters } ?: return
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
