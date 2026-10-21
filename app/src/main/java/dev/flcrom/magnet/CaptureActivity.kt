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

/** Grid capture: he rests the magnet on each lit dot; stable readings get averaged and saved. */
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
    private var stableSince = 0L
    private val grabbed = ArrayDeque<FloatArray>()
    private var grabbing = false
    private lateinit var v: CapView

    inner class CapView(context: Context) : View(context) {
        private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; style = Paint.Style.FILL }
        private val pDone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x4c, 0xaf, 0x50); style = Paint.Style.FILL }
        private val pCur = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 7f }
        private val pCurFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 56f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
        private val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 42f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
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
                    cv.drawText("train magnet", cx, height * 0.4f, pTxt)
                    cv.drawText("keep the magnet far away", cx, height * 0.47f, pSub)
                    cv.drawText("tap to zero + start", cx, height * 0.53f, pSub)
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
                    cv.drawText("point ${idx + 1} / ${COLS * ROWS}", cx, height - 110f, pTxt)
                    cv.drawText(if (grabbing) "hold still..." else "rest magnet flat on the lit dot", cx, height - 56f, pSub)
                    cv.drawText("tap anywhere = redo last point", cx, 110f, pSub)
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
            if (e.actionMasked != MotionEvent.ACTION_DOWN) return@setOnTouchListener true
            when (phase) {
                0 -> { zeroBaseline(); phase = 1; idx = 0; view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
                1 -> { if (idx > 0) { idx--; caps[idx] = null } ; grabbed.clear(); grabbing = false }  // redo
            }
            true
        }
        setContentView(v)
    }

    private fun zeroBaseline() {
        val a = recent.toList()
        if (a.size >= 10) b0 = FloatArray(3) { i -> a.sumOf { it[i].toDouble() }.toFloat() / a.size }
        if (b0 == null) b0 = floatArrayOf(0f, 0f, 0f)
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
        val now = SystemClock.elapsedRealtime()
        val fixed = rot(rotQ, floatArrayOf(e.values[0], e.values[1], e.values[2]), false)
        recent.addLast(fixed); while (recent.size > 40) recent.removeFirst()
        if (phase != 1 || idx >= COLS * ROWS) return
        val base = b0 ?: return
        // phone-frame residual, identical to FerrofluidActivity's S.res (raw minus baseline rotated to phone frame)
        val bp = rot(rotQ, base, true)
        val m = floatArrayOf(e.values[0], e.values[1], e.values[2])
        val res = floatArrayOf(m[0] - bp[0], m[1] - bp[1], m[2] - bp[2])
        if (grabbing) {
            grabbed.addLast(res)
            if (grabbed.size >= 50) {
                val g = grabbed.toList()
                val mean = FloatArray(3) { i -> g.sumOf { it[i].toDouble() }.toFloat() / g.size }
                var sd = 0f
                for (s in g) sd += (s[0] - mean[0]).pow(2) + (s[1] - mean[1]).pow(2) + (s[2] - mean[2]).pow(2)
                caps[idx] = mean; stds[idx] = sqrt(sd / g.size)
                idx++; grabbing = false; grabbed.clear(); stableSince = 0
                v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                if (idx >= COLS * ROWS) { saveModel(); phase = 2 }
            }
            return
        }
        // stability gate: magnet clearly present and quiet for 0.6s
        val mag = len3(res)
        if (recent.size >= 30 && mag > 12f) {
            val last = recent.toList().takeLast(30)
            val m0 = FloatArray(3) { i -> last.sumOf { it[i].toDouble() }.toFloat() / last.size }
            var sd = 0f
            for (s in last) sd += (s[0] - m0[0]).pow(2) + (s[1] - m0[1]).pow(2) + (s[2] - m0[2]).pow(2)
            sd = sqrt(sd / last.size)
            if (sd < 4f) {
                if (stableSince == 0L) stableSince = now
                if (now - stableSince > 600) { grabbing = true; grabbed.clear() }
            } else stableSince = 0
        } else stableSince = 0
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
