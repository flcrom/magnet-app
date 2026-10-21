package dev.flcrom.magnet

import org.json.JSONObject
import java.io.File
import kotlin.math.*

/** Screen-grid magnet position model trained by CaptureActivity (magnet_grid.json).
 *  Per-point best-scale match (same scale ladder as the old classifier) + kNN(3)
 *  inverse-distance interpolation. k of the nearest fingerprint is the normalized
 *  strength (1 = resting on the glass like training, <1 = hovering above). */
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

    class Est(val nx: Float, val ny: Float, val conf: Float, val k: Float)

    fun estimate(res: FloatArray): Est? {
        if (pts.size < 3) return null
        var d0 = Double.MAX_VALUE; var d1 = Double.MAX_VALUE; var d2m = Double.MAX_VALUE
        var p0: Pt? = null; var p1: Pt? = null; var p2: Pt? = null
        var k0 = 1f; var sg0 = 1f
        for (p in pts) {
            val fn = sqrt(p.r[0] * p.r[0] + p.r[1] * p.r[1] + p.r[2] * p.r[2])
            var bD = Double.MAX_VALUE; var bK = 1f; var bSg = 1f
            for (k in KS) {
                val dx = res[0] - k * p.r[0]; val dy = res[1] - k * p.r[1]; val dz = res[2] - k * p.r[2]
                val dd = (dx * dx + dy * dy + dz * dz).toDouble()
                if (dd < bD) { bD = dd; bK = k; bSg = 0.10f * k * fn + 4f + p.std }
            }
            if (bD < d2m) {
                if (bD < d1) {
                    if (bD < d0) { d2m = d1; p2 = p1; d1 = d0; p1 = p0; d0 = bD; p0 = p; k0 = bK; sg0 = bSg }
                    else { d2m = d1; p2 = p1; d1 = bD; p1 = p }
                } else { d2m = bD; p2 = p }
            }
        }
        if (p0 == null || p1 == null || p2 == null) return null
        var sw = 0.0; var sx = 0.0; var sy = 0.0
        val dd = doubleArrayOf(d0, d1, d2m); val pp = arrayOf(p0, p1, p2)
        for (i in 0 until 3) {
            val w = 1.0 / (sqrt(dd[i]) + 1.0)
            sw += w; sx += w * pp[i]!!.nx; sy += w * pp[i]!!.ny
        }
        val conf = exp(-d0 / (2.0 * sg0 * sg0)).toFloat()
        return Est((sx / sw).toFloat(), (sy / sw).toFloat(), conf, k0)
    }

    companion object {
        private val KS = floatArrayOf(0.5f, 0.6f, 0.72f, 0.85f, 1f, 1.18f, 1.4f, 1.7f, 2f)
        fun load(filesDir: File): GridModel? {
            val f = File(filesDir, "magnet_grid.json")
            return try { if (f.exists()) GridModel(f) else null } catch (t: Throwable) { null }
        }
    }
}
