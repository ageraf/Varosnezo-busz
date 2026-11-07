package hu.abris.varosnezo.geo

import hu.abris.varosnezo.LatLng
import kotlin.math.*

object Geo {
    private const val R = 6_371_000.0
    private const val M_PER_DEG = 111_320.0

    fun dist(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * R * asin(sqrt(h))
    }

    /** Google encoded polyline dekódolása (Google: 5, MOTIS/Transitous: 6 tizedes). */
    fun decodePolyline(enc: String, precision: Int = 5): List<LatLng> {
        val f = 10.0.pow(precision)
        val out = ArrayList<LatLng>()
        var i = 0
        var lat = 0
        var lng = 0
        fun next(): Int {
            var shift = 0
            var result = 0
            var b: Int
            do {
                b = enc[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            return if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
        }
        while (i < enc.length) {
            lat += next()
            lng += next()
            out.add(LatLng(lat / f, lng / f))
        }
        return out
    }

    /** Kumulált távolság minden ponthoz az útvonal elejétől. */
    fun cumulative(path: List<LatLng>): DoubleArray {
        val c = DoubleArray(path.size)
        for (i in 1 until path.size) c[i] = c[i - 1] + dist(path[i - 1], path[i])
        return c
    }

    fun pointAt(path: List<LatLng>, cum: DoubleArray, d: Double): LatLng {
        if (d <= 0.0) return path.first()
        if (d >= cum.last()) return path.last()
        val i = cum.indexOfFirst { it > d }
        val a = path[i - 1]
        val b = path[i]
        val seg = cum[i] - cum[i - 1]
        val t = if (seg > 0) (d - cum[i - 1]) / seg else 0.0
        return LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
    }

    /** Egyenletes mintavétel az útvonalon stepM méterenként. */
    fun sample(path: List<LatLng>, stepM: Double): List<LatLng> {
        if (path.size < 2) return path
        val cum = cumulative(path)
        val out = mutableListOf<LatLng>()
        var d = 0.0
        while (d < cum.last()) {
            out += pointAt(path, cum, d)
            d += stepM
        }
        out += path.last()
        return out
    }

    data class Projection(val segIndex: Int, val alongM: Double, val offM: Double)

    private fun projectSeg(p: LatLng, path: List<LatLng>, cum: DoubleArray, i: Int): Projection {
        val k = cos(Math.toRadians(p.lat)) * M_PER_DEG
        val a = path[i]
        val b = path[i + 1]
        val bx = (b.lng - a.lng) * k
        val by = (b.lat - a.lat) * M_PER_DEG
        val px = (p.lng - a.lng) * k
        val py = (p.lat - a.lat) * M_PER_DEG
        val len2 = bx * bx + by * by
        val t = if (len2 > 0) ((px * bx + py * by) / len2).coerceIn(0.0, 1.0) else 0.0
        return Projection(i, cum[i] + t * (cum[i + 1] - cum[i]), hypot(px - t * bx, py - t * by))
    }

    /** Pont vetítése az útvonalra: hol tart rajta (alongM) és milyen messze van tőle (offM). */
    fun project(p: LatLng, path: List<LatLng>, cum: DoubleArray, fromSeg: Int = 0): Projection {
        var best = Projection(0, 0.0, Double.MAX_VALUE)
        val start = fromSeg.coerceIn(0, max(0, path.size - 2))
        for (i in start until path.size - 1) {
            val pr = projectSeg(p, path, cum, i)
            if (pr.offM < best.offM) best = pr
        }
        return best
    }

    /** Az első olyan szakasz fromSeg után, ami tolM-en belül van (körjáratnál nem ugrik a végére). */
    fun projectFirst(p: LatLng, path: List<LatLng>, cum: DoubleArray, fromSeg: Int, tolM: Double = 40.0): Projection {
        val start = fromSeg.coerceIn(0, max(0, path.size - 2))
        for (i in start until path.size - 1) {
            val pr = projectSeg(p, path, cum, i)
            if (pr.offM <= tolM) return pr
        }
        return project(p, path, cum, fromSeg)
    }

    /** Az útvonal d0..d1 méter közötti része. */
    fun slice(path: List<LatLng>, cum: DoubleArray, d0: Double, d1: Double): List<LatLng> {
        val out = mutableListOf(pointAt(path, cum, d0))
        for (i in path.indices) if (cum[i] > d0 && cum[i] < d1) out += path[i]
        out += pointAt(path, cum, d1)
        return out
    }
}
