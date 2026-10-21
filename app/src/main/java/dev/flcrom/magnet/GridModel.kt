package dev.flcrom.magnet

import org.json.JSONObject
import java.io.File
import kotlin.math.*

/** Magnet position model: dense fingerprints (28-pt grid + slide samples) compressed into a
 *  40x60 screen-cell table, tracked over time with a grid Bayes filter.
 *  - Cell fingerprint: inverse-distance weighted mean of the 6 nearest training samples.
 *  - Measurement: per-cell best-scale distance over a wide ladder (k 0.06..1.8), so a weaker
 *    hovering magnet still matches by vector shape; winning cell's k = normalized strength
 *    (1 = resting on the glass, <1 = hovering) used for eruption height.
 *  - Tracking: posterior over cells, Gaussian motion blur (magnet moves continuously),
 *    small uniform mixing for re-acquisition; estimate = posterior mean. Continuity resolves
 *    the fingerprint collisions that break instantaneous kNN (mirror jumps).
 *  Est.conf stays a 0..1-ish gate for existing callers (mapped from posterior peak). */
class GridModel private constructor(
    private val fp: FloatArray,          // NC*NR*3 cell fingerprints
    private val fpMag: FloatArray        // NC*NR fingerprint magnitudes
) {
    class Est(val nx: Float, val ny: Float, val conf: Float, val k: Float)

    private val post = FloatArray(NC * NR) { 1f / (NC * NR) }
    private val like = FloatArray(NC * NR)
    private val buf = FloatArray(NC * NR)
    private var lastAt = 0L

    /** One sensor residual (baseline removed, uT). Null when the magnet is absent (|res| < 8uT),
     *  which also decays the tracker back toward uniform so a reappearance starts fresh. */
    @Synchronized
    fun estimate(res: FloatArray): Est? {
        val now = System.currentTimeMillis()
        if (now - lastAt > 700L) post.fill(1f / (NC * NR))   // stroke gap: restart tracking
        lastAt = now
        val rn = sqrt(res[0] * res[0] + res[1] * res[1] + res[2] * res[2])
        if (rn < 3f) return null   // low presence gate near the noise floor; confidence gates quality downstream
        val sig = max(4f, 0.25f * rn)
        val inv2s2 = 1f / (2f * sig * sig)
        // measurement likelihood per cell (best scale per cell)
        var bestK = 1f; var bestD = Float.MAX_VALUE; var bestC = 0
        for (c in 0 until NC * NR) {
            val fx = fp[c * 3]; val fy = fp[c * 3 + 1]; val fz = fp[c * 3 + 2]
            var bd = Float.MAX_VALUE; var bk = 1f
            for (k in KS) {
                val dx = res[0] - k * fx; val dy = res[1] - k * fy; val dz = res[2] - k * fz
                val dd = dx * dx + dy * dy + dz * dz
                if (dd < bd) { bd = dd; bk = k }
            }
            like[c] = exp(-bd * inv2s2)
            if (bd < bestD) { bestD = bd; bestK = bk; bestC = c }
        }
        // motion blur (separable gaussian, sigma MOT cells) then Bayes update + uniform mixing
        blur(post, buf)   // in-place: post = blurred posterior, buf = scratch
        val mix = 0.01f / (NC * NR)
        var sum = 0f
        for (c in 0 until NC * NR) { val v = post[c] * like[c] + mix; like[c] = v; sum += v }
        var sx = 0f; var sy = 0f; var peak = 0f
        for (c in 0 until NC * NR) {
            val v = like[c] / sum; post[c] = v
            if (v > peak) peak = v
            sx += v * ((c % NC) + 0.5f) / NC
            sy += v * ((c / NC) + 0.5f) / NR
        }
        // map posterior peak to the legacy 0..1 conf gate (locked-on peak ~0.01..0.1)
        val conf = (peak / 0.01f).coerceIn(0f, 1f)   // 1.0 = locked; <0.4 = freeze cursor, <0.12 = not present
        return Est(sx, sy, conf, bestK)
    }

    /** In-place separable Gaussian blur of a, using scratch; result left in a. */
    private fun blur(a: FloatArray, scratch: FloatArray) {
        val r = KERNEL_R
        for (y in 0 until NR) {
            val row = y * NC
            for (x in 0 until NC) {
                var acc = 0f
                for (j in -r..r) acc += a[row + (x + j).coerceIn(0, NC - 1)] * KERNEL[j + r]
                scratch[row + x] = acc
            }
        }
        for (y in 0 until NR) {
            val row = y * NC
            for (x in 0 until NC) {
                var acc = 0f
                for (j in -r..r) acc += scratch[(y + j).coerceIn(0, NR - 1) * NC + x] * KERNEL[j + r]
                a[row + x] = acc
            }
        }
    }

    companion object {
        private const val NC = 40
        private const val NR = 60
        private const val MOT = 3.0f           // motion blur sigma, cells (~100Hz stream)
        private val KERNEL_R = (MOT * 3).toInt()
        private val KERNEL = FloatArray(KERNEL_R * 2 + 1).also { k ->
            var s = 0f
            for (j in -KERNEL_R..KERNEL_R) { val v = exp(-0.5f * (j / MOT) * (j / MOT)); k[j + KERNEL_R] = v; s += v }
            for (j in k.indices) k[j] /= s
        }
        private val KS = floatArrayOf(0.06f, 0.09f, 0.13f, 0.2f, 0.3f, 0.45f, 0.65f, 0.85f, 1f)  // one-sided: never stronger than on-glass (k>1 mainly let collisions win)

        fun load(filesDir: File): GridModel? {
            return try {
                // training points: 28-pt grid + dense slide samples
                class TP(val nx: Float, val ny: Float, val r: FloatArray)
                val list = ArrayList<TP>()
                val f = File(filesDir, "magnet_grid.json")
                if (f.exists()) {
                    val arr = JSONObject(f.readText()).getJSONArray("points")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i); val r = o.getJSONArray("r")
                        list.add(TP(o.getDouble("nx").toFloat(), o.getDouble("ny").toFloat(),
                            floatArrayOf(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat())))
                    }
                }
                val sf = File(filesDir, "magnet_slide.jsonl")
                if (sf.exists()) {
                    val extra = ArrayList<TP>()
                    sf.forEachLine { line ->
                        try {
                            val o = JSONObject(line); val r = o.getJSONArray("r")
                            val rv = floatArrayOf(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat())
                            val m = sqrt(rv[0] * rv[0] + rv[1] * rv[1] + rv[2] * rv[2])
                            if (m > 8f) extra.add(TP(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), rv))
                        } catch (_: Throwable) {}
                    }
                    val stride = max(1, extra.size / 8000)
                    for (i in extra.indices step stride) list.add(extra[i])
                }
                if (list.size < 3) return null
                // build cell fingerprints: inverse-distance weighted mean of 6 nearest training points
                val fp = FloatArray(NC * NR * 3)
                val fm = FloatArray(NC * NR)
                val d = FloatArray(list.size)
                for (cy in 0 until NR) for (cx in 0 until NC) {
                    val px = (cx + 0.5f) / NC; val py = (cy + 0.5f) / NR
                    for (i in list.indices) {
                        val dx = list[i].nx - px; val dy = list[i].ny - py
                        d[i] = dx * dx + dy * dy
                    }
                    var sw = 0f; var ax = 0f; var ay = 0f; var az = 0f
                    for (n in 0 until 6) {
                        var bi = 0
                        for (i in 1 until list.size) if (d[i] < d[bi]) bi = i
                        val w = 1f / (sqrt(d[bi]) + 0.02f)
                        sw += w; ax += w * list[bi].r[0]; ay += w * list[bi].r[1]; az += w * list[bi].r[2]
                        d[bi] = Float.MAX_VALUE
                    }
                    val c = cy * NC + cx
                    fp[c * 3] = ax / sw; fp[c * 3 + 1] = ay / sw; fp[c * 3 + 2] = az / sw
                    fm[c] = sqrt(fp[c * 3] * fp[c * 3] + fp[c * 3 + 1] * fp[c * 3 + 1] + fp[c * 3 + 2] * fp[c * 3 + 2])
                }
                GridModel(fp, fm)
            } catch (t: Throwable) { null }
        }
    }
}
