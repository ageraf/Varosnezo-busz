package hu.abris.varosnezo.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import hu.abris.varosnezo.LatLng
import hu.abris.varosnezo.Poi
import hu.abris.varosnezo.data.Http
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

private class FitState {
    var fittedPath: List<LatLng>? = null
    var centeredOnMe = false
}

/** OpenStreetMap térkép: útvonal, látnivalók (koppintásra a leírás), saját pozíció. */
@Composable
fun TourMap(
    path: List<LatLng>?,
    pois: List<Poi>,
    played: Set<Long>,
    me: LatLng?,
    follow: Boolean,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val fit = remember { FitState() }
    val meIcon = remember {
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF1E88E5.toInt())
            setStroke(5, Color.WHITE)
            setSize(40, 40)
        }
    }
    val map = remember {
        Configuration.getInstance().apply {
            load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = "VarosnezoBusz/0.1 (${Http.CONTACT})"
            osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(47.4979, 19.0402))
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose {
            map.onPause()
            map.onDetach()
        }
    }

    AndroidView(factory = { map }, modifier = modifier.clipToBounds(), update = { m ->
        m.overlays.clear()
        if (path != null && path.size >= 2) {
            m.overlays.add(Polyline(m).apply {
                setPoints(path.map { GeoPoint(it.lat, it.lng) })
                outlinePaint.color = 0xFF6750A4.toInt()
                outlinePaint.strokeWidth = 12f
            })
        }
        pois.forEach { p ->
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(p.pos.lat, p.pos.lng)
                title = p.title
                snippet = p.text
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                if (p.id in played) alpha = 0.45f
            })
        }
        if (me != null) {
            m.overlays.add(Marker(m).apply {
                position = GeoPoint(me.lat, me.lng)
                icon = meIcon
                title = "Itt vagy"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            })
        }

        if (follow && me != null) {
            m.controller.animateTo(GeoPoint(me.lat, me.lng))
        } else if (path != null && path.size >= 2 && fit.fittedPath !== path) {
            fit.fittedPath = path
            val box = BoundingBox.fromGeoPoints(path.map { GeoPoint(it.lat, it.lng) })
            if (m.width > 0) m.zoomToBoundingBox(box, false, 64)
            else m.addOnFirstLayoutListener { _, _, _, _, _ -> m.zoomToBoundingBox(box, false, 64) }
        } else if (path == null && me != null && !fit.centeredOnMe) {
            fit.centeredOnMe = true
            m.controller.setCenter(GeoPoint(me.lat, me.lng))
        }
        m.invalidate()
    })
}
