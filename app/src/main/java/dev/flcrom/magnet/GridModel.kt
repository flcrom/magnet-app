package dev.flcrom.magnet

import android.util.Log
import org.json.JSONObject
import java.io.File
import kotlin.math.*

/** Magnet position model: per-cell PER-STROKE prototype fingerprints + grid Bayes tracker.
 *  - Strokes split by >1.5s time gaps; each stroke's per-cell mean fingerprint is a separate
 *    prototype (up to 8 per cell), so mixed magnet orientations never average into mush.
 *  - Per-stroke zero alignment: each stroke is corrected by the median offset vs stroke 0 at
 *    same-position pairs with cos>0.9 (kills manual-zero differences between sessions).
 *  - Measurement: min over prototypes and k in {0.06..1.0} (one-sided: never stronger than
 *    on-glass) of ||res - k*fp||; likelihood exp(-d^2/2sig^2), sig = max(4, 0.25*|res|).
 *  - Tracking: posterior over 40x60 cells, Gaussian motion blur sigma 3 cells, 1% uniform
 *    mixing, posterior-mean estimate. conf = posterior peak/0.01 (legacy 0..1 gate).
 *  Reference holdout (10557 samples): stroke6 10.5%/25.7% med/p90, stroke1 17.1%/45.5%. */
class GridModel private constructor(
    private val proto: FloatArray,   // NC*NR*PMAX*3
    private val pcount: IntArray     // NC*NR
) {
    class Est(val nx: Float, val ny: Float, val conf: Float, val k: Float)

    private val post = FloatArray(NC * NR) { 1f / (NC * NR) }
    private val like = FloatArray(NC * NR)
    private val buf = FloatArray(NC * NR)
    private var lastAt = 0L

    @Synchronized
    fun estimate(res: FloatArray): Est? {
        val now = System.currentTimeMillis()
        if (now - lastAt > 700L) post.fill(1f / (NC * NR))
        lastAt = now
        val rn = sqrt(res[0] * res[0] + res[1] * res[1] + res[2] * res[2])
        if (rn < 3f) return null   // low presence gate near the noise floor; conf gates quality
        val sig = max(4f, 0.25f * rn)
        val inv2s2 = 1f / (2f * sig * sig)
        var bestK = 1f
        for (c in 0 until NC * NR) {
            val pc = pcount[c]
            var bd = Float.MAX_VALUE; var bk = 1f
            val base = c * PMAX * 3
            for (p in 0 until pc) {
                val fx = proto[base + p * 3]; val fy = proto[base + p * 3 + 1]; val fz = proto[base + p * 3 + 2]
                for (k in KS) {
                    val dx = res[0] - k * fx; val dy = res[1] - k * fy; val dz = res[2] - k * fz
                    val dd = dx * dx + dy * dy + dz * dz
                    if (dd < bd) { bd = dd; bk = k }
                }
            }
            like[c] = exp(-bd * inv2s2)
        }
        blur(post, buf)
        val mix = 0.01f / (NC * NR)
        var sum = 0f
        for (c in 0 until NC * NR) { val v = post[c] * like[c] + mix; like[c] = v; sum += v }
        var sx = 0f; var sy = 0f; var peak = 0f; var peakC = 0
        for (c in 0 until NC * NR) {
            val v = like[c] / sum; post[c] = v
            if (v > peak) { peak = v; peakC = c }
            sx += v * ((c % NC) + 0.5f) / NC
            sy += v * ((c / NC) + 0.5f) / NR
        }
        // k at the peak cell (normalized strength for eruption height)
        if (pcount[peakC] > 0) {
            var bd = Float.MAX_VALUE
            val base = peakC * PMAX * 3
            for (p in 0 until pcount[peakC]) {
                val fx = proto[base + p * 3]; val fy = proto[base + p * 3 + 1]; val fz = proto[base + p * 3 + 2]
                for (k in KS) {
                    val dx = res[0] - k * fx; val dy = res[1] - k * fy; val dz = res[2] - k * fz
                    val dd = dx * dx + dy * dy + dz * dz
                    if (dd < bd) { bd = dd; bestK = k }
                }
            }
        }
        val conf = (peak / 0.01f).coerceIn(0f, 1f)
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
        private const val TAG = "GridModel"
        private const val NC = 40
        private const val NR = 60
        private const val PMAX = 8
        private const val MOT = 3.0f
        private val KERNEL_R = (MOT * 3).toInt()
        private val KERNEL = FloatArray(KERNEL_R * 2 + 1).also { k ->
            var s = 0f
            for (j in -KERNEL_R..KERNEL_R) { val v = exp(-0.5f * (j / MOT) * (j / MOT)); k[j + KERNEL_R] = v; s += v }
            for (j in k.indices) k[j] /= s
        }
        private val KS = floatArrayOf(0.06f, 0.09f, 0.13f, 0.2f, 0.3f, 0.45f, 0.65f, 0.85f, 1f)  // one-sided: k>1 mainly let collisions win

        fun load(filesDir: File): GridModel? {
            val t0 = System.currentTimeMillis()
            return try {
                val proto = FloatArray(NC * NR * PMAX * 3)
                val pcount = IntArray(NC * NR)
                fun addProto(nx: Float, ny: Float, r: FloatArray) {
                    val cx = (nx * NC).toInt().coerceIn(0, NC - 1); val cy = (ny * NR).toInt().coerceIn(0, NR - 1)
                    val c = cy * NC + cx
                    if (pcount[c] < PMAX) {
                        val b = (c * PMAX + pcount[c]) * 3
                        proto[b] = r[0]; proto[b + 1] = r[1]; proto[b + 2] = r[2]; pcount[c]++
                    }
                }
                val gf = File(filesDir, "magnet_grid.json")
                if (gf.exists()) {
                    val arr = JSONObject(gf.readText()).getJSONArray("points")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i); val r = o.getJSONArray("r")
                        addProto(o.getDouble("nx").toFloat(), o.getDouble("ny").toFloat(),
                            floatArrayOf(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat()))
                    }
                }
                // slide samples with stroke structure
                class S(val x: Float, val y: Float, val r: FloatArray, val ts: Long)
                val all = ArrayList<S>()
                val sf = File(filesDir, "magnet_slide.jsonl")
                if (sf.exists()) {
                    sf.forEachLine { line ->
                        try {
                            val o = JSONObject(line); val r = o.getJSONArray("r")
                            val rv = floatArrayOf(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat())
                            val m = sqrt(rv[0] * rv[0] + rv[1] * rv[1] + rv[2] * rv[2])
                            if (m > 8f) all.add(S(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), rv,
                                if (o.has("ts")) o.getLong("ts") else 0L))
                        } catch (_: Throwable) {}
                    }
                }
                if (all.size < 3 && pcount.sum() < 3) return null
                // split strokes by >1.5s gaps
                val strokes = ArrayList<IntArray>()   // indices into all
                var cur = ArrayList<Int>()
                for (i in all.indices) {
                    if (cur.isNotEmpty() && all[i].ts - all[cur.last()].ts > 1500) {
                        strokes.add(cur.toIntArray()); cur = ArrayList()
                    }
                    cur.add(i)
                }
                if (cur.isNotEmpty()) strokes.add(cur.toIntArray())
                // per-stroke zero alignment vs the first big stroke
                val refIdx = strokes.firstOrNull { it.size >= 50 } ?: intArrayOf()
                val aligned = Array(all.size) { i -> all[i].r }
                var nAligned = 0
                for (si in strokes.indices) {
                    val st = strokes[si]
                    if (st === refIdx || st.size < 50 || refIdx.isEmpty()) continue
                    // nearest stroke-0 sample for each sample (brute force, 2D)
                    val dxs = ArrayList<Float>(); val dys = ArrayList<Float>(); val dzs = ArrayList<Float>()
                    for (i in st) {
                        var bd = Float.MAX_VALUE; var bj = -1
                        for (j in refIdx) {
                            val dx = all[i].x - all[j].x; val dy = all[i].y - all[j].y
                            val dd = dx * dx + dy * dy
                            if (dd < bd) { bd = dd; bj = j }
                        }
                        if (bj >= 0 && bd < 0.03f * 0.03f) {
                            val a = all[i].r; val b = all[bj].r
                            val na = sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2]); val nb = sqrt(b[0]*b[0]+b[1]*b[1]+b[2]*b[2])
                            val cos = (a[0]*b[0]+a[1]*b[1]+a[2]*b[2]) / max(1e-6f, na * nb)
                            val ratio = na / max(1e-6f, nb)
                            // cos alone pairs hover vectors with contact ones (huge bogus offsets)
                            if (cos > 0.9f && ratio in 0.5f..2f) { dxs.add(a[0]-b[0]); dys.add(a[1]-b[1]); dzs.add(a[2]-b[2]) }
                        }
                    }
                    if (dxs.size > 30) {
                        fun med(v: ArrayList<Float>): Float { val s = v.sorted(); return s[s.size / 2] }
                        var ox = med(dxs); var oy = med(dys); var oz = med(dzs)
                        // sanity: a zero offset is a few uT, never hundreds - reject and leave the stroke alone
                        if (sqrt(ox*ox+oy*oy+oz*oz) > 25f) {
                            Log.i(TAG, "stroke $si alignment REJECTED (offset ${"%.0f".format(sqrt(ox*ox+oy*oy+oz*oz))}uT)")
                            ox = 0f; oy = 0f; oz = 0f
                        }
                        if (ox != 0f || oy != 0f || oz != 0f) {
                            for (i in st) {
                                val a = aligned[i]
                                aligned[i] = floatArrayOf(a[0]-ox, a[1]-oy, a[2]-oz)
                            }
                            nAligned++
                        }
                        Log.i(TAG, "stroke $si aligned by [${"%.1f".format(ox)},${"%.1f".format(oy)},${"%.1f".format(oz)}]uT (${dxs.size} pairs)")
                    }
                }
                // per-stroke prototypes: 8-NN weighted mean per cell, only where the stroke has data
                for (st in strokes) {
                    if (st.size < 8) {
                        for (i in st) addProto(all[i].x, all[i].y, aligned[i])
                        continue
                    }
                    for (cy in 0 until NR) for (cx in 0 until NC) {
                        val px = (cx + 0.5f) / NC; val py = (cy + 0.5f) / NR
                        // 8 nearest of this stroke
                        var sw = 0f; var ax = 0f; var ay = 0f; var az = 0f; var nearest = Float.MAX_VALUE
                        val dd = FloatArray(st.size)
                        for (n in st.indices) {
                            val dx = all[st[n]].x - px; val dy = all[st[n]].y - py
                            dd[n] = dx * dx + dy * dy
                        }
                        for (k2 in 0 until 8) {
                            var bi = 0
                            for (n in 1 until st.size) if (dd[n] < dd[bi]) bi = n
                            if (k2 == 0) nearest = dd[bi]
                            val w = 1f / (sqrt(dd[bi]) + 0.02f)
                            val rv = aligned[st[bi]]
                            sw += w; ax += w * rv[0]; ay += w * rv[1]; az += w * rv[2]
                            dd[bi] = Float.MAX_VALUE
                        }
                        if (nearest < 0.12f * 0.12f) addProto(px, py, floatArrayOf(ax / sw, ay / sw, az / sw))
                    }
                }
                Log.i(TAG, "load: ${all.size} samples, ${strokes.size} strokes, $nAligned aligned, ${pcount.sum()} protos, ${System.currentTimeMillis() - t0}ms")
                if (pcount.sum() < 3) null else GridModel(proto, pcount)
            } catch (t: Throwable) { Log.w(TAG, "load failed", t); null }
        }
    }
}
