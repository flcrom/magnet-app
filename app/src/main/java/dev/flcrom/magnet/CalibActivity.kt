package dev.flcrom.magnet

import android.app.Activity
import android.app.AlertDialog
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
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

/** Physics recorder: fresh guided session, own file, no old data loaded.
 *  Flow: ZERO (magnet far) -> POINT grid (hold magnet on each dot, auto-capture when stable)
 *  -> SLIDE (touch-recorded continuous passes, pause/resume, undo last stroke)
 *  -> HOVER (spacer stack 0/2/5/10mm, reduced grid, height tagged).
 *  File: magnet_calib_<yyyyMMdd_HHmmss>.jsonl, meta header + sample lines.
 *  Discard = rename to .discarded (archive, never delete).
 *  Zero sanity: far grid dots (y>=0.55) must read <22 uT vs baseline (dialog:
 *  discard+re-zero or keep), and the field must return under 12 uT within 10 s
 *  after each capture or a sticky contamination banner shows (both logged as
 *  "warn" lines in the session file). */
class CalibActivity : Activity(), SensorEventListener {
    private lateinit var sm: SensorManager
    private var rotQ = floatArrayOf(0f, 0f, 0f, 1f)
    private var phase = 0          // 0 zero, 1 point, 2 slide, 3 hover
    private var b0: FloatArray? = null
    private var b0std = 0f
    private val recent = ArrayDeque<FloatArray>()   // raw rotated samples for zeroing
    private var live = floatArrayOf(0f, 0f, 0f)
    private var liveAt = 0L
    private var magCount = 0; private var magRate = 0; private var rateAt = 0L

    private lateinit var session: String
    private lateinit var f: File
    private var writer: BufferedWriter? = null
    private var samples = 0
    private var warnUntil = 0L

    // zero-contamination guards: a zero taken with the magnet nearby passes the
    // |B| range check (bias vector can keep magnitude earthlike) but poisons every
    // residual. Guard 1: far-grid dots must read < 22 uT (genuine dipole signal
    // that far from the top-left sensor is ~10-15 uT). Guard 2: between captures
    // the field must fall back under 12 uT within 10 s of lifting the magnet.
    private var farWarned = false        // user picked KEEP ANYWAY once this session
    private var farDialogShowing = false
    private var highSince = 0L           // resNorm continuously >= 18 uT since
    private var driftWarn = false        // sticky banner until field returns to baseline

    // point mode
    private lateinit var dots: FloatArray   // normalized x,y per dot, serpentine
    private var dotIdx = 0
    private val captured = HashSet<Int>()
    private val win = ArrayDeque<FloatArray>()
    private var winStart = 0L

    // slide mode
    private var touching = false; private var paused = false
    private var tx = 0f; private var ty = 0f
    private var strokeLines = 0                  // lines of current open stroke
    private val strokeSizes = ArrayDeque<Int>()  // completed strokes (line counts)
    private var strokeSamples = 0

    // hover mode
    private val heights = intArrayOf(0, 2, 5, 10)
    private var hIdx = 0
    private lateinit var hdots: FloatArray
    private var hdotIdx = 0
    private val hcaptured = HashSet<Int>()

    private lateinit var v: CalView

    companion object {
        private const val W_MM = 63.9f
        private const val H_MM = 142.1f
    }

    // ---------- model helpers ----------
    private fun resid(): FloatArray {
        val b = b0 ?: return floatArrayOf(0f, 0f, 0f)
        return floatArrayOf(live[0] - b[0], live[1] - b[1], live[2] - b[2])
    }
    private fun resNorm() = len3(resid())

    private fun buildDots() {
        val list = ArrayList<Float>()
        var y = 0.13f
        while (y <= 0.50f) { var x = 0.10f; while (x <= 0.90f) { list.add(x); list.add(y); x += 0.20f }; y += 0.14f }
        y = 0.55f
        while (y <= 0.96f) { var x = 0.08f; while (x <= 0.92f) { list.add(x); list.add(y); x += 0.14f }; y += 0.082f }
        dots = list.toFloatArray()
    }
    private fun buildHDots() {
        val list = ArrayList<Float>()
        for (y in floatArrayOf(0.12f, 0.30f, 0.55f, 0.75f, 0.92f)) {
            var x = 0.15f; while (x <= 0.90f) { list.add(x); list.add(y); x += 0.35f }
        }
        hdots = list.toFloatArray()
    }

    // ---------- file ----------
    private fun line(o: JSONObject) { try { writer?.write(o.toString()); writer?.newLine(); samples++ } catch (_: Throwable) {} }

    private fun openSession() {
        session = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        f = File(filesDir, "magnet_calib_$session.jsonl")
        writer = BufferedWriter(FileWriter(f, true))
        val b = b0!!
        line(JSONObject()
            .put("type", "meta").put("session", session).put("guard", "off")
            .put("magnet", "cylindrical, same pole down")
            .put("W_mm", W_MM.toDouble()).put("H_mm", H_MM.toDouble())
            .put("zero", JSONArray(listOf(b[0], b[1], b[2]))).put("zero_std", b0std.toDouble()))
        farWarned = false; farDialogShowing = false; highSince = 0L; driftWarn = false
    }

    private fun discardSession() {
        try { writer?.close() } catch (_: Throwable) {}
        writer = null
        val d = File(filesDir, "magnet_calib_$session.jsonl.discarded")
        try { f.renameTo(d) } catch (_: Throwable) {}
        phase = 0; b0 = null; samples = 0; captured.clear(); hcaptured.clear()
        strokeSizes.clear(); dotIdx = 0; hdotIdx = 0
        farWarned = false; farDialogShowing = false; highSince = 0L; driftWarn = false
        v.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    // ---------- activity ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        buildDots(); buildHDots()
        v = CalView(this)
        setContentView(v)
        // touch on the VIEW: e.x/e.y are view-local (Activity.onTouchEvent delivers
        // window coords offset by the status bar, which silently broke button hits)
        v.setOnTouchListener { _, e -> onCalTouch(e); true }
    }

    override fun onResume() {
        super.onResume()
        val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: return
        sm.registerListener(this, mag, 10000)
    }
    override fun onPause() { super.onPause(); sm.unregisterListener(this); try { writer?.flush() } catch (_: Throwable) {} }
    override fun onDestroy() { super.onDestroy(); try { writer?.close() } catch (_: Throwable) {} }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
        // RAW sensor frame: rigid with the phone - the game rotation vector's yaw drifts
        // over a multi-minute session and was scrambling transverse field direction.
        // Phone lies flat for recording; moment is along phone -z (pole down).
        val fixed = floatArrayOf(e.values[0], e.values[1], e.values[2])
        live = fixed; liveAt = SystemClock.elapsedRealtime()
        magCount++; val now = SystemClock.elapsedRealtime()
        if (now - rateAt > 1000) { magRate = magCount; magCount = 0; rateAt = now }
        recent.addLast(fixed); if (recent.size > 40) recent.removeFirst()

        if (phase == 1 || phase == 3) {
            val rn = resNorm()
            if (rn >= 18f) { if (highSince == 0L) highSince = now }
            else if (rn < 12f) { highSince = 0L; driftWarn = false }
            if (!driftWarn && highSince != 0L && now - highSince > 10000) {
                driftWarn = true
                line(JSONObject().put("type", "warn").put("kind", "baseline_not_returning")
                    .put("resid_uT", rn.toDouble()).put("ts", System.currentTimeMillis()))
                v.performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
        }

        when (phase) {
            1, 3 -> pointSample(fixed, now)
            2 -> slideSample(fixed, now)
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ---------- point / hover capture ----------
    private fun pointSample(fixed: FloatArray, now: Long) {
        if (resNorm() < 8f) { win.clear(); winStart = 0; return }
        if (winStart == 0L) winStart = now
        win.addLast(fixed)
        while (win.size > 35) win.removeFirst()
        if (now - winStart >= 1000 && win.size >= 20) {
            val n = win.size
            val mean = FloatArray(3) { i -> win.sumOf { it[i].toDouble() }.toFloat() / n }
            var sd = 0f
            for (w in win) sd += (w[0]-mean[0]).pow(2) + (w[1]-mean[1]).pow(2) + (w[2]-mean[2]).pow(2)
            sd = sqrt(sd / n)
            if (sd < 2.5f) {
                val arr = if (phase == 1) dots else hdots
                val idx = if (phase == 1) dotIdx else hdotIdx
                if (idx * 2 + 1 < arr.size) {
                    val rn = len3(floatArrayOf(mean[0] - b0!![0], mean[1] - b0!![1], mean[2] - b0!![2]))
                    if (phase == 1 && !farWarned && !farDialogShowing && arr[idx * 2 + 1] >= 0.55f && rn >= 22f) {
                        // far dot must be quiet: genuine dipole signal this far from the
                        // top-left sensor is ~10-15 uT; >=22 uT means the zero was taken
                        // with the magnet too close and every residual is biased.
                        farDialogShowing = true
                        line(JSONObject().put("type", "warn").put("kind", "far_dot_contamination")
                            .put("i", idx).put("resid_uT", rn.toDouble()).put("ts", System.currentTimeMillis()))
                        try { writer?.flush() } catch (_: Throwable) {}
                        AlertDialog.Builder(this)
                            .setTitle("zero looks contaminated")
                            .setMessage("This far dot reads %.0f uT from baseline; a dot this far from the sensor should read under ~22 uT. The magnet was probably too close when you zeroed, so every measurement in this session is biased.\n\nBest: discard, take the magnet to another room, zero, and redo the grid.".format(rn))
                            .setPositiveButton("DISCARD + RE-ZERO") { _, _ -> farDialogShowing = false; discardSession() }
                            .setNegativeButton("KEEP ANYWAY") { _, _ -> farDialogShowing = false; farWarned = true; recordPoint(idx, mean, sd, n) }
                            .setCancelable(false).show()
                    } else {
                        recordPoint(idx, mean, sd, n)
                    }
                }
                win.clear(); winStart = 0
            } else if (now - winStart > 4000) { win.clear(); winStart = 0; warnUntil = now + 1200 }
        }
    }
    private fun recordPoint(idx: Int, mean: FloatArray, sd: Float, n: Int) {
        val arr = if (phase == 1) dots else hdots
        val o = JSONObject().put("type", "p")
            .put("mode", if (phase == 1) "point" else "hover")
            .put("i", idx)
            .put("x", arr[idx * 2].toDouble()).put("y", arr[idx * 2 + 1].toDouble())
            .put("h", if (phase == 3) heights[hIdx] else 0)
            .put("r", JSONArray(listOf(mean[0], mean[1], mean[2])))
            .put("r_std", sd.toDouble()).put("n", n)
            .put("ts", System.currentTimeMillis())
        line(o)
        try { writer?.flush() } catch (_: Throwable) {}
        if (phase == 1) { captured.add(dotIdx); dotIdx = nextFree(dots, captured) } else { hcaptured.add(hdotIdx); hdotIdx = nextFree(hdots, hcaptured) }
        v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        highSince = 0L; driftWarn = false
    }

    private fun nextFree(arr: FloatArray, done: HashSet<Int>): Int {
        val n = arr.size / 2
        for (i in 0 until n) if (!done.contains(i)) return i
        return n
    }

    // ---------- slide ----------
    private fun slideSample(fixed: FloatArray, now: Long) {
        if (!touching || paused || writer == null) return
        line(JSONObject().put("type", "s").put("mode", "slide")
            .put("x", (tx / v.width).toDouble()).put("y", (ty / v.height).toDouble())
            .put("h", 0)
            .put("r", JSONArray(listOf(fixed[0], fixed[1], fixed[2])))
            .put("ts", System.currentTimeMillis()))
        strokeLines++; strokeSamples++
        if (strokeSamples % 50 == 0) try { writer?.flush() } catch (_: Throwable) {}
    }

    private fun undoStroke() {
        val last = strokeSizes.removeLastOrNull() ?: return
        try {
            writer?.close()
            val lines = f.readLines()
            val keepn = (lines.size - last).coerceAtLeast(1)
            f.writeText(lines.take(keepn).joinToString("\n") + "\n")
            samples -= last
            writer = BufferedWriter(FileWriter(f, true))
            v.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } catch (_: Throwable) { writer = BufferedWriter(FileWriter(f, true)) }
    }

    // ---------- touch ----------
    private fun onCalTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (phase == 0) { tryZero(); return }
                val r = v.hit(e.x, e.y)
                when (r) {
                    "point" -> { phase = 1; return }
                    "slide" -> { phase = 2; paused = false; return }
                    "hover" -> { phase = 3; return }
                    "discard" -> { discardSession(); return }
                    "pause" -> { paused = !paused; return }
                    "undo" -> { undoStroke(); return }
                    "h" -> { hIdx = (hIdx + 1) % heights.size; return }
                }
                if (phase == 2 && !paused) { touching = true; tx = e.x; ty = e.y; strokeLines = 0; strokeSamples = 0 }
            }
            MotionEvent.ACTION_MOVE -> if (touching) { tx = e.x; ty = e.y }
            MotionEvent.ACTION_UP -> {
                if (touching) { touching = false; strokeSizes.addLast(strokeLines); try { writer?.flush() } catch (_: Throwable) {} }
            }
        }
    }

    private fun tryZero() {
        val a = recent.toList()
        if (a.size < 20) { warnUntil = SystemClock.elapsedRealtime() + 1200; return }
        val mean = FloatArray(3) { i -> a.sumOf { it[i].toDouble() }.toFloat() / a.size }
        var sd = 0f
        for (w in a) sd += (w[0]-mean[0]).pow(2) + (w[1]-mean[1]).pow(2) + (w[2]-mean[2]).pow(2)
        sd = sqrt(sd / a.size)
        val norm = len3(mean)
        if (norm < 25f || norm > 80f || sd > 3f) { warnUntil = SystemClock.elapsedRealtime() + 2000; v.performHapticFeedback(HapticFeedbackConstants.REJECT); return }
        b0 = mean; b0std = sd
        openSession()
        phase = 1
        v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    // ---------- view ----------
    inner class CalView(context: Context) : View(context) {
        private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x44, 0x44, 0x44); style = Paint.Style.FILL }
        private val pDone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x4c, 0xaf, 0x50); style = Paint.Style.FILL }
        private val pCur = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 7f }
        private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 52f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
        private val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 38f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
        private val pWarn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xff, 0x70, 0x43); textSize = 38f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
        private val pBtn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x33, 0x33, 0x33); style = Paint.Style.FILL }
        private val pBtnT = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
        private val pTrail = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; strokeWidth = 5f; style = Paint.Style.STROKE }
        private var buttons = ArrayList<Triple<RectF, String, String>>()

        fun hit(x: Float, y: Float): String? {
            for (b in buttons) if (b.first.contains(x, y)) return b.second
            return null
        }

        private fun btn(cv: Canvas, l: Float, t: Float, r: Float, bo: Float, id: String, label: String, active: Boolean = false) {
            pBtn.color = if (active) Color.rgb(0x0b, 0x5a, 0x5a) else Color.rgb(0x33, 0x33, 0x33)
            cv.drawRoundRect(RectF(l, t, r, bo), 18f, 18f, pBtn)
            cv.drawText(label, (l + r) / 2, (t + bo) / 2 + 13, pBtnT)
            buttons.add(Triple(RectF(l, t, r, bo), id, label))
        }

        override fun onDraw(cv: Canvas) {
            cv.drawColor(Color.BLACK)
            buttons.clear()
            val cx = width / 2f; val w = width.toFloat(); val h = height.toFloat()
            val rn = resNorm()
            val now = SystemClock.elapsedRealtime()
            val healthy = now - liveAt < 500 && magRate >= 15
            when (phase) {
                0 -> {
                    cv.drawText("record (physics)", cx, h * 0.30f, pTxt)
                    cv.drawText("session: new file, no old data", cx, h * 0.36f, pSub)
                    cv.drawText("keep the magnet FAR away", cx, h * 0.44f, pSub)
                    cv.drawText("field: %.1f uT".format(len3(live)), cx, h * 0.52f,
                        if (len3(live) in 25f..80f) pSub else pWarn)
                    cv.drawText("tap to zero + start", cx, h * 0.60f, pSub)
                    if (now < warnUntil) cv.drawText("field not calm/earthlike - move magnet away", cx, h * 0.70f, pWarn)
                }
                else -> {
                    // HUD
                    cv.drawText("n=$samples  |res|=%.0fuT  %dHz".format(rn, magRate), cx, 60f,
                        if (healthy) pSub else pWarn)
                    val bw = w / 4 - 14
                    btn(cv, 8f, 90f, 8f + bw, 160f, "point", "POINT", phase == 1)
                    btn(cv, 16f + bw, 90f, 16f + 2 * bw, 160f, "slide", "SLIDE", phase == 2)
                    btn(cv, 24f + 2 * bw, 90f, 24f + 3 * bw, 160f, "hover", "HOVER", phase == 3)
                    btn(cv, 32f + 3 * bw, 90f, 32f + 4 * bw, 160f, "discard", "DISCARD")
                    when (phase) {
                        1 -> {
                            val n = dots.size / 2
                            cv.drawText("hold magnet on the flashing dot (${captured.size}/$n)", cx, 215f, pSub)
                            if (driftWarn) cv.drawText("field not returning to baseline (%.0f uT) - zero may be contaminated".format(rn), cx, h * 0.52f, pWarn)
                            for (i in 0 until n) {
                                val px = dots[i * 2] * w; val py = dots[i * 2 + 1] * h
                                when {
                                    captured.contains(i) -> cv.drawCircle(px, py, 13f, pDone)
                                    i == dotIdx -> {
                                        val rad = 20f + 6f * sin(now / 180.0).toFloat()
                                        cv.drawCircle(px, py, rad, pCur); cv.drawCircle(px, py, 8f, pCur)
                                    }
                                    else -> cv.drawCircle(px, py, 10f, pDot)
                                }
                            }
                            if (now < warnUntil) cv.drawText("hold still...", cx, h - 40f, pWarn)
                            if (captured.size == n) cv.drawText("grid done - switch mode or discard", cx, h / 2, pTxt)
                        }
                        2 -> {
                            cv.drawText(if (paused) "paused - drag to resume via PAUSE" else "touch and slide (contact only)", cx, 215f, pSub)
                            btn(cv, 8f, h - 170f, w / 2 - 8f, h - 90f, "pause", if (paused) "RESUME" else "PAUSE")
                            btn(cv, w / 2 + 8f, h - 170f, w - 8f, h - 90f, "undo", "UNDO STROKE")
                            if (touching) cv.drawCircle(tx, ty, 24f, pTrail)
                        }
                        3 -> {
                            btn(cv, 8f, 180f, w / 2 + 60f, 250f, "h", "height: ${heights[hIdx]}mm (tap)")
                            cv.drawText("stack spacers, magnet on dot, pole down", w / 2 + 90f, 224f, pSub)
                            val n = hdots.size / 2
                            for (i in 0 until n) {
                                val px = hdots[i * 2] * w; val py = hdots[i * 2 + 1] * h
                                when {
                                    hcaptured.contains(i) -> cv.drawCircle(px, py, 13f, pDone)
                                    i == hdotIdx -> {
                                        val rad = 20f + 6f * sin(now / 180.0).toFloat()
                                        cv.drawCircle(px, py, rad, pCur); cv.drawCircle(px, py, 8f, pCur)
                                    }
                                    else -> cv.drawCircle(px, py, 10f, pDot)
                                }
                            }
                            if (now < warnUntil) cv.drawText("hold still...", cx, h - 40f, pWarn)
                            if (driftWarn) cv.drawText("field not returning to baseline (%.0f uT) - zero may be contaminated".format(rn), cx, h * 0.52f, pWarn)
                        }
                    }
                }
            }
            postInvalidateDelayed(50)
        }
    }
}
