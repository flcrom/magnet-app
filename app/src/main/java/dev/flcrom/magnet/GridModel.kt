package dev.flcrom.magnet

import org.json.JSONObject
import java.io.File
import kotlin.math.*

/** Screen-grid magnet position model trained by CaptureActivity (magnet_grid.json).
 *  Gaussian-weighted interpolation of the 28 phone-frame residual fingerprints. */
class GridModel(file: File) {
    private class Pt(val nx: Float, val ny: Float, val r: FloatArray, val std: Float)
    private val pts: List<Pt>

    init {
        val root = JSONObject(file.readText())
        val arr = root.getJSONArray("points")
        val list = ArrayList<Pt>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val r = o.getJSONArray("r")
            list.add(Pt(
                o.getDouble("nx").toFloat(), o.getDouble("ny").toFloat(),
                floatArrayOf(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat()),
                o.getDouble("std").toFloat()))
        }
        pts = list
    }

    class Est(val nx: Float, val ny: Float, val conf: Float)

    /** Weighted position estimate; conf = best point weight in 0..1 (1 = on a trained point). */
    fun estimate(res: FloatArray): Est? {
        if (pts.isEmpty()) return null
        var sw = 0.0; var sx = 0.0; var sy = 0.0; var best = 0.0
        for (p in pts) {
            val fn = sqrt(p.r[0] * p.r[0] + p.r[1] * p.r[1] + p.r[2] * p.r[2])
            val sg = (0.10f * fn + 4f + p.std).toDouble()
            val dx = res[0] - p.r[0]; val dy = res[1] - p.r[1]; val dz = res[2] - p.r[2]
            val d2 = (dx * dx + dy * dy + dz * dz).toDouble()
            val w = exp(-d2 / (2.0 * sg * sg))
            if (w > best) best = w
            sw += w; sx += w * p.nx; sy += w * p.ny
        }
        if (sw <= 1e-12) return null
        return Est((sx / sw).toFloat(), (sy / sw).toFloat(), best.toFloat())
    }

    companion object {
        fun load(filesDir: File): GridModel? {
            val f = File(filesDir, "magnet_grid.json")
            return try { if (f.exists()) GridModel(f) else null } catch (t: Throwable) { null }
        }
    }
}
