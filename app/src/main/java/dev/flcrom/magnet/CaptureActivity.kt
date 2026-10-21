package dev.flcrom.magnet

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.*

/** Grid capture, manual mode: he rests the magnet on the lit dot, taps to record,
 *  hold anywhere to redo the last point. No auto-capture. */
class CaptureActivity : Activity(), SensorEventListener {
    private val COLS = 4; private val ROWS = 7
    private lateinit var sm: SensorManager
    private var rotQ = floatArrayOf(0f, 0f, 0f, 1f)
    private var phase = 0            // 0 = intro (keep magnet away, tap start), 1 = capturing, 2 = done
    private var b0: FloatArray? = null
    private var idx = 0
    private val caps = arrayOfNulls<FloatArray>(COLS * ROWS)   // mean residual per point
    private val stds = FloatArray(COLS * ROWS)
    private val recent = ArrayDeque<FloatArray>()
    private val grabbed = ArrayDeque<FloatArray>()
    private var grabbing = false
    private var liveMag = 0f       // live residual magnitude for feedback
    private var downAt = 0L
    private var warnUntil = 0L
    private lateinit var v: CapView

    inner class CapView(context: Context) : View(context) {
        private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; style = Paint.Style.FILL }
        private val pDone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x4c, 0xaf, 0x50); style = Paint.Style.FILL }
        private val pCur = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 7f }
        private val pCurFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 56f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
        private val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 42f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
        private val pWarn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xff, 0x70, 0x43); textSize = 40f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
        fun pt(i: Int): FloatArray {
            val c = i % COLS; val r = i / COLS
            val cc = if (r % 2 == 0) c else COLS - 1 - c   // serpentine
            return floatArrayOf(width * (0.14f + 0.72f * cc / (COLS - 1)), height * (0.10f + 0.78f * r / (ROWS - 1)))
        }
        override fun onDraw(cv: Canvas) {
            cv.drawColor(Color.BLACK)
            val cx = width / 2f
            when (phase) {
                0 -> {
                    cv.drawText("train magnet", cx, height * 0.38f, pTxt)
                    cv.drawText("keep the magnet far away", cx, height * 0.46f, pSub)
                    cv.drawText("tap to zero + start", cx, height * 0.52f, pSub)
                    if (SystemClock.elapsedRealtime() < warnUntil)
                        cv.drawText("magnet too close - move it away", cx, height * 0.62f, pWarn)
                }
                1 -> {
                    for (i in 0 until COLS * ROWS) {
                        val p = pt(i)
                        if (caps[i] != null) cv.drawCircle(p[0], p[1], 22f, pDone)
                        else cv.drawCircle(p[0], p[1], 14f, pDot)
                    }
                    if (idx < COLS * ROWS) {
                        val p = pt(idx)
                        val pulse = 34f + 10f * sin(SystemClock.elapsedRealtime() / 180.0).toFloat()
                        cv.drawCircle(p[0], p[1], 12f, pCurFill)
                        cv.drawCircle(p[0], p[1], pulse, pCur)
                    }
                    cv.drawText("hold anywhere = redo last point", cx, 110f, pSub)
                    cv.drawText("point ${idx + 1} / ${COLS * ROWS}", cx, height - 160f, pTxt)
                    cv.drawText(if (grabbing) "recording..." else "rest magnet, then tap", cx, height - 96f, pSub)
                    cv.drawText("field now: ${"%.0f".format(liveMag)} uT", cx, height - 44f, pSub)
                }
                2 -> {
                    cv.drawText("done - model saved", cx, height * 0.45f, pTxt)
                    cv.drawText("open Ferrofluid and try the magnet", cx, height * 0.53f, pSub)
                }
            }
            postInvalidateDelayed(50)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        v = CapView(this)
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downAt = SystemClock.elapsedRealtime(); true }
                MotionEvent.ACTION_UP -> {
                    val held = SystemClock.elapsedRealtime() - downAt > 500
                    when (phase) {
                        0 -> if (!held) tryStart(view)
                        1 -> if (held) { if (idx > 0) { idx--; caps[idx] = null }; grabbed.clear(); grabbing = false }
                             else if (!grabbing && idx < COLS * ROWS) { grabbing = true; grabbed.clear() }
                        else -> {}
                    }
                    true
                }
                else -> true
            }
        }
        setContentView(v)
    }

    private fun tryStart(view: View) {
        val a = recent.toList()
        b0 = if (a.size >= 10) FloatArray(3) { i -> a.sumOf { it[i].toDouble() }.toFloat() / a.size }
             else floatArrayOf(0f, 0f, 0f)
        val base = b0 ?: return
        val norm = len3(base)
        if (norm < 25f || norm > 80f) {
            // baseline is not plain Earth field - the magnet is too close
            b0 = null
            warnUntil = SystemClock.elapsedRealtime() + 2500
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            return
        }
        phase = 1; idx = 0; grabbing = false; grabbed.clear()
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    override fun onResume() {
        super.onResume()
        val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: return
        sm.registerListener(this, mag, 10000)
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sm.registerListener(this, it, 10000) }
    }
    override fun onPause() { super.onPause(); sm.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            val w = if (e.values.size > 3) e.values[3] else sqrt(max(0f, 1f - e.values[0].pow(2) - e.values[1].pow(2) - e.values[2].pow(2)))
            rotQ = floatArrayOf(e.values[0], e.values[1], e.values[2], w); return
        }
        if (e.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
        val fixed = rot(rotQ, floatArrayOf(e.values[0], e.values[1], e.values[2]), false)
        recent.addLast(fixed); while (recent.size > 40) recent.removeFirst()
        val base = b0 ?: return
        // phone-frame residual, identical to FerrofluidActivity's S.res (raw minus baseline rotated to phone frame)
        val bp = rot(rotQ, base, true)
        val m = floatArrayOf(e.values[0], e.values[1], e.values[2])
        val res = floatArrayOf(m[0] - bp[0], m[1] - bp[1], m[2] - bp[2])
        liveMag = len3(res)
        if (!grabbing || idx >= COLS * ROWS) return
        grabbed.addLast(res)
        if (grabbed.size >= 50) {
            val g = grabbed.toList()
            val mean = FloatArray(3) { i -> g.sumOf { it[i].toDouble() }.toFloat() / g.size }
            var sd = 0f
            for (s in g) sd += (s[0] - mean[0]).pow(2) + (s[1] - mean[1]).pow(2) + (s[2] - mean[2]).pow(2)
            caps[idx] = mean; stds[idx] = sqrt(sd / g.size)
            idx++; grabbing = false; grabbed.clear()
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            if (idx >= COLS * ROWS) { saveModel(); phase = 2 }
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun saveModel() {
        val pts = JSONArray()
        for (i in 0 until COLS * ROWS) {
            val c = i % COLS; val r = i / COLS
            val cc = if (r % 2 == 0) c else COLS - 1 - c
            val o = JSONObject()
            o.put("nx", 0.14 + 0.72 * cc / (COLS - 1.0))   // x fraction of width
            o.put("ny", 0.10 + 0.78 * r / (ROWS - 1.0))   // y fraction of height
            val m = caps[i]!!
            o.put("r", JSONArray(listOf(m[0].toDouble(), m[1].toDouble(), m[2].toDouble())))
            o.put("std", stds[i].toDouble())
            pts.put(o)
        }
        val root = JSONObject()
        root.put("version", 1); root.put("cols", COLS); root.put("rows", ROWS)
        root.put("capturedAt", System.currentTimeMillis()); root.put("points", pts)
        File(filesDir, "magnet_grid.json").writeText(root.toString())
    }
}
