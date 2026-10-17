package dev.flcrom.magnet

import kotlin.math.*

/** Learned 24-direction position classifier. Ported from the web version (/m/m2.js). */
object Classifier {
    // Mean field change (uT, phone axes) with the magnet touching the glass at direction i*15 deg
    // (0 = top of screen, clockwise) on a ring of about 2.9 cm around the screen center, averaged over two passes.
    private val FP = arrayOf(
        floatArrayOf(254.33f, 40.76f, -8.41f), floatArrayOf(100.47f, 2.68f, -30.56f), floatArrayOf(66.22f, -3.14f, -23.75f),
        floatArrayOf(50.93f, -12.19f, -18.65f), floatArrayOf(41.92f, -27.56f, -14.11f), floatArrayOf(36.15f, -34.25f, -12.54f),
        floatArrayOf(28.88f, -35.18f, -11.69f), floatArrayOf(20.68f, -28.12f, -8.5f), floatArrayOf(14.99f, -20.52f, -7.15f),
        floatArrayOf(8.62f, -14.56f, -5.27f), floatArrayOf(5.5f, -10.66f, -4.17f), floatArrayOf(5.47f, -11.05f, -3.92f),
        floatArrayOf(3.99f, -12.77f, -3.34f), floatArrayOf(2.36f, -13.41f, -3.44f), floatArrayOf(2.27f, -17.0f, -4.7f),
        floatArrayOf(7.04f, -31.33f, -9.09f), floatArrayOf(10.1f, -56.64f, -12.27f), floatArrayOf(17.62f, -91.61f, -11.12f),
        floatArrayOf(2.66f, -123.67f, -0.87f), floatArrayOf(75.09f, -116.47f, 133.31f), floatArrayOf(-472.72f, -259.29f, 213.79f),
        floatArrayOf(-485.4f, 593.56f, 67.23f), floatArrayOf(71.02f, 547.29f, -1.17f), floatArrayOf(331.73f, 265.65f, -8.58f)
    )
    private val FPN = FloatArray(24) { sqrt(FP[it][0] * FP[it][0] + FP[it][1] * FP[it][1] + FP[it][2] * FP[it][2]) }
    private val KS = floatArrayOf(0.5f, 0.6f, 0.72f, 0.85f, 1f, 1.18f, 1.4f, 1.7f, 2f)

    class Result(val ang: Float, val conc: Float, val fit: Float, val k: Float, val best: Int)

    fun classify(r: FloatArray): Result {
        val ll = DoubleArray(24) { -1e9 }
        val kb = FloatArray(24) { 1f }
        for (i in 0 until 24) {
            val sg = 0.2 * FPN[i] + 3
            for (k in KS) {
                val dx = r[0] - k * FP[i][0]; val dy = r[1] - k * FP[i][1]; val dz = r[2] - k * FP[i][2]
                val l = -(dx * dx + dy * dy + dz * dz) / (2 * k * k * sg * sg) - 3 * ln(k * sg) - 0.5 * (ln(k.toDouble()) / 0.4).pow(2)
                if (l > ll[i]) { ll[i] = l; kb[i] = k }
            }
        }
        var mx = -1e9; var bi = 0
        for (i in 0 until 24) if (ll[i] > mx) { mx = ll[i]; bi = i }
        val w = DoubleArray(24) { exp(ll[it] - mx) }
        val sum = w.sum()
        var c = 0.0; var s = 0.0
        for (i in 0 until 24) { val p = w[i] / sum; val a = i * PI / 12; c += p * cos(a); s += p * sin(a) }
        val sg = 0.2 * FPN[bi] + 3; val k = kb[bi]
        val d = sqrt((r[0] - k * FP[bi][0]).pow(2) + (r[1] - k * FP[bi][1]).pow(2) + (r[2] - k * FP[bi][2]).pow(2)) / (k * sg)
        val ang = ((atan2(s, c) * 180 / PI) + 360) % 360
        return Result(ang.toFloat(), hypot(c, s).toFloat(), d.toFloat(), k, bi)
    }
}
