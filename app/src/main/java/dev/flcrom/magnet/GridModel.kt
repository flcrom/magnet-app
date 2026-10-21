package dev.flcrom.magnet

import org.json.JSONObject
import java.io.File
import kotlin.math.*

/** Screen-grid magnet position model trained by CaptureActivity (magnet_grid.json).
 *  k-nearest-neighbor (k=3) inverse-distance interpolation over the 28 phone-frame
 *  residual fingerprints; confidence from the nearest fingerprint's distance.
 *  Chosen over a Gaussian kernel by leave-one-out on the real capture (17.7% vs 23.7%
 *  median screen error). */
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

    fun estimate(res: FloatArray): Est? {
        if (pts.size < 3) return null
        val best = arrayOfNulls<Pt>(3)
        val bestD = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        for (p in pts) {
            val dx = res[0] - p.r[0]; val dy = res[1] - p.r[1]; val dz = res[2] - p.r[2]
            val d2 = (dx * dx + dy * dy + dz * dz).toDouble()
            if (d2 < bestD[2]) {
                if (d2 < bestD[1]) {
                    if (d2 < bestD[0]) { bestD[2] = bestD[1]; best[2] = best[1]; bestD[1] = bestD[0]; best[1] = best[0]; bestD[0] = d2; best[0] = p }
                    else { bestD[2] = bestD[1]; best[2] = best[1]; bestD[1] = d2; best[1] = p }
                } else { bestD[2] = d2; best[2] = p }
            }
        }
        var sw = 0.0; var sx = 0.0; var sy = 0.0
        for (i in 0 until 3) {
            val w = 1.0 / (sqrt(bestD[i]) + 1.0)
            sw += w; sx += w * best[i]!!.nx; sy += w * best[i]!!.ny
        }
        val p0 = best[0]!!
        val sg = 0.10f * sqrt(p0.r[0] * p0.r[0] + p0.r[1] * p0.r[1] + p0.r[2] * p0.r[2]) + 4f + p0.std
        val conf = exp(-bestD[0] / (2.0 * sg * sg)).toFloat()
        return Est((sx / sw).toFloat(), (sy / sw).toFloat(), conf)
    }

    companion object {
        fun load(filesDir: File): GridModel? {
            val f = File(filesDir, "magnet_grid.json")
            return try { if (f.exists()) GridModel(f) else null } catch (t: Throwable) { null }
        }
    }
}
