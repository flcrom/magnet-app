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
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale
import kotlin.math.*

/** Slide training: he slides the magnet on the glass while touching; the touchscreen
 *  supplies ground-truth x,y and the magnetometer the 3-axis residual. Strokes draw as
 *  continuous white lines, the model's prediction as a continuous cyan line. Explicit
 *  PAUSE and WIPE buttons at the bottom. */
class SlideActivity : Activity(), SensorEventListener {
    private lateinit var sm: SensorManager
    private var rotQ = floatArrayOf(0f, 0f, 0f, 1f)
    private var phase = 0            // 0 = zero prompt, 1 = recording
    private var paused = false
    private var b0: FloatArray? = null
    private val recent = ArrayDeque<FloatArray>()
    private var liveMag = 0f
    private var touching = false
    private var touchingButton = false
    private var strokeStart = 0L
    private var strokeOpen = false
    private var tx = 0f; private var ty = 0f
    private var writer: BufferedWriter? = null
    private var samples = 0
    private var sinceFlush = 0
    private var warnUntil = 0L
    private var grid: GridModel? = null
    private var vw = 1; private var vh = 1
    private lateinit var v: SlideView

    inner class SlideView(context: Context) : View(context) {
        val strokes = ArrayList<ArrayList<TrailPt>>()  // ground truth polylines
        val estPts = ArrayList<TrailPt>()              // model prediction polyline (breaks on time gaps)
        private val pPath = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 7f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        private val pEst = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x22, 0xd3, 0xee); style = Paint.Style.STROKE; strokeWidth = 7f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        private val pEstDim = Paint(pEst).apply { alpha = 80 }   // low-conf estimates shown dimmed, not hidden
        private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 52f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
        private val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 38f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
        private val pWarn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xff, 0x70, 0x43); textSize = 40f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
        private val pBtn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x21, 0x24, 0x2b); style = Paint.Style.FILL }
        private val pBtnT = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }

        fun btnH() = (height * 0.09f).coerceAtLeast(150f)
        var hoverMode = false   // HOVER: finger on glass, magnet above it; samples tagged "h":1
        fun pauseRect() = RectF(24f, height - btnH() - 20f, width / 3f - 16f, height - 20f)
        fun wipeRect() = RectF(width / 3f + 8f, height - btnH() - 20f, width * 2f / 3f - 8f, height - 20f)
        fun modeRect() = RectF(width * 2f / 3f + 16f, height - btnH() - 20f, width - 24f, height - 20f)
        fun inButton(x: Float, y: Float) = pauseRect().contains(x, y) || wipeRect().contains(x, y) || modeRect().contains(x, y)

        fun beginStroke(x: Float, y: Float) { strokes.add(arrayListOf(TrailPt(x, y, SystemClock.elapsedRealtime()))) }
        fun addPath(x: Float, y: Float) { strokes.lastOrNull()?.add(TrailPt(x, y, SystemClock.elapsedRealtime())) }
        fun addEst(x: Float, y: Float, conf: Float) { estPts.add(TrailPt(x, y, SystemClock.elapsedRealtime(), conf)) }
        fun clearAll() { strokes.clear(); estPts.clear() }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { vw = w; vh = h }
        override fun onDraw(cv: Canvas) {
            cv.drawColor(Color.BLACK)
            val cx = width / 2f
            if (phase == 0) {
                cv.drawText("slide train", cx, height * 0.38f, pTxt)
                cv.drawText("keep the magnet far away", cx, height * 0.46f, pSub)
                cv.drawText("tap to zero + start", cx, height * 0.52f, pSub)
                if (SystemClock.elapsedRealtime() < warnUntil)
                    cv.drawText("magnet too close - move it away", cx, height * 0.62f, pWarn)
            } else {
                for (s in strokes) {
                    val path = Path()
                    s.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                    cv.drawPath(path, pPath)
                }
                if (estPts.size > 1) {
                    // dim underlay: every estimate that passed the 0.25 floor
                    val pathD = Path(); var penD = false; var lastTD = 0L
                    // bright overlay: only confident (>=0.4) runs
                    val path = Path(); var pen = false; var lastT = 0L
                    for (p in estPts) {
                        if (!penD || p.t - lastTD > 500) { pathD.moveTo(p.x, p.y); penD = true } else pathD.lineTo(p.x, p.y)
                        lastTD = p.t
                        if (p.c >= 0.4f) {
                            if (!pen || p.t - lastT > 500) { path.moveTo(p.x, p.y); pen = true } else path.lineTo(p.x, p.y)
                        } else pen = false
                        lastT = p.t
                    }
                    cv.drawPath(pathD, pEstDim)
                    cv.drawPath(path, pEst)
                }
                if (touching) cv.drawCircle(tx, ty, 22f, pDot)
                cv.drawText("samples $samples", cx, 120f, pTxt)
                cv.drawText("field now: ${"%.0f".format(liveMag)} uT", cx, 176f, pSub)
                val st = when {
                    !touching -> "lift magnet+finger off? touching screen = recording"
                    liveMag <= 3.5f -> "touch ok, magnet not detected"
                    else -> "recording - white: true path, cyan: model"
                }
                cv.drawText(st, cx, height - btnH() - 40f, if (touching && liveMag > 3.5f) pSub else pWarn)
                // buttons
                cv.drawRoundRect(pauseRect(), 24f, 24f, pBtn)
                cv.drawRoundRect(wipeRect(), 24f, 24f, pBtn)
                val pBtnMode = Paint(pBtn).apply { color = if (hoverMode) Color.rgb(0x6b, 0x4e, 0x00) else Color.rgb(0x21, 0x24, 0x2b) }
                cv.drawRoundRect(modeRect(), 24f, 24f, pBtnMode)
                cv.drawText(if (paused) "RESUME" else "PAUSE", pauseRect().centerX(), pauseRect().centerY() + 16f, pBtnT)
                cv.drawText("WIPE", wipeRect().centerX(), wipeRect().centerY() + 16f, pBtnT)
                cv.drawText(if (hoverMode) "HOVER" else "TOUCH", modeRect().centerX(), modeRect().centerY() + 16f, pBtnT)
                if (paused) cv.drawText("paused", cx, height * 0.5f, pTxt)
            }
            postInvalidateDelayed(50)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        v = SlideView(this)
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (phase == 1 && v.inButton(e.x, e.y)) { touchingButton = true; return@setOnTouchListener true }
                    touching = true; tx = e.x; ty = e.y
                    strokeStart = SystemClock.elapsedRealtime(); strokeOpen = false
                }
                MotionEvent.ACTION_MOVE -> { if (touching) { tx = e.x; ty = e.y } }
                MotionEvent.ACTION_UP -> {
                    if (touchingButton) {
                        touchingButton = false
                        if (v.pauseRect().contains(e.x, e.y)) { paused = !paused; view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
                        else if (v.wipeRect().contains(e.x, e.y)) wipe(view)
                        else if (v.modeRect().contains(e.x, e.y)) { v.hoverMode = !v.hoverMode; view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
                        return@setOnTouchListener true
                    }
                    val wasTouching = touching
                    touching = false; strokeOpen = false
                    if (phase == 0 && wasTouching) tryStart(view)
                    flush()
                }
            }
            true
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
            b0 = null
            warnUntil = SystemClock.elapsedRealtime() + 2500
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            return
        }
        phase = 1; paused = false
        writer = BufferedWriter(FileWriter(File(filesDir, "magnet_slide.jsonl"), true))
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    private fun wipe(view: View) {
        try { writer?.close() } catch (_: Throwable) {}
        File(filesDir, "magnet_slide.jsonl").delete()
        writer = BufferedWriter(FileWriter(File(filesDir, "magnet_slide.jsonl"), true))
        samples = 0; v.clearAll()
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    private fun flush() { try { writer?.flush() } catch (_: Throwable) {}; sinceFlush = 0 }

    override fun onResume() {
        super.onResume()
        grid = GridModel.load(filesDir)
        val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: return
        sm.registerListener(this, mag, 10000)
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { sm.registerListener(this, it, 10000) }
    }
    override fun onPause() { super.onPause(); sm.unregisterListener(this); flush() }
    override fun onDestroy() { super.onDestroy(); try { writer?.close() } catch (_: Throwable) {} }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            val w = if (e.values.size > 3) e.values[3] else sqrt(max(0f, 1f - e.values[0].pow(2) - e.values[1].pow(2) - e.values[2].pow(2)))
            rotQ = floatArrayOf(e.values[0], e.values[1], e.values[2], w); return
        }
        if (e.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
        val fixed = rot(rotQ, floatArrayOf(e.values[0], e.values[1], e.values[2]), false)
        recent.addLast(fixed); while (recent.size > 40) recent.removeFirst()
        val base = b0 ?: return
        val bp = rot(rotQ, base, true)
        val m = floatArrayOf(e.values[0], e.values[1], e.values[2])
        val res = floatArrayOf(m[0] - bp[0], m[1] - bp[1], m[2] - bp[2])
        liveMag = len3(res)
        // slow re-zero (same as the plane): creep baseline when clearly no magnet
        if (liveMag < 1.2f) {
            val fix = rot(rotQ, res, false)
            val bf = rot(rotQ, base, false)
            b0 = rot(rotQ, floatArrayOf(bf[0] + fix[0] * 0.0006f, bf[1] + fix[1] * 0.0006f, bf[2] + fix[2] * 0.0006f), true)
        }
        if (phase != 1 || paused) return
        val contact = touching && SystemClock.elapsedRealtime() - strokeStart > 120 && liveMag > 3.5f
        if (contact) {
            val w2 = writer ?: return
            if (!strokeOpen) { v.beginStroke(tx, ty); strokeOpen = true }
            w2.write(String.format(Locale.US, "{\"x\":%.4f,\"y\":%.4f,\"r\":[%.2f,%.2f,%.2f],\"ts\":%d%s}",
                tx / vw, ty / vh, res[0], res[1], res[2], System.currentTimeMillis(), if (v.hoverMode) ",\"h\":1" else ""))
            w2.newLine()
            samples++
            if (++sinceFlush >= 50) flush()
            v.addPath(tx, ty)
            // conf>=0.4: bright verified trail; 0.25..0.4: dimmed hint (measured 9-23% err there, honest); <0.25: hidden
            grid?.estimate(res)?.let { if (it.conf > 0.25f) v.addEst(it.nx * vw, it.ny * vh, it.conf) }
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

data class TrailPt(val x: Float, val y: Float, val t: Long, val c: Float = 1f)
