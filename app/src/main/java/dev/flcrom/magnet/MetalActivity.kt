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
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.*

/**
 * Metal: one screen of chrome UI components that lean toward a magnet.
 * Visual design from Jakub Antalik's "Metal effect" video; sensor pipeline
 * (zeroing, residual, learned classifier, spring) shared with the blob app.
 */
class MetalActivity : Activity(), SensorEventListener {
    private val S = State()
    private lateinit var sm: SensorManager
    private lateinit var view: MetalView
    private var rotQ = floatArrayOf(0f, 0f, 0f, 1f)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        S.flip = getSharedPreferences("m", MODE_PRIVATE).getInt("flip", 1)
        view = MetalView(this, S,
            onZero = {
                val ok = zero(); view.say(if (ok) "zeroed" else "no fresh sensor data")
            },
            onFlip = {
                S.flip = -S.flip
                getSharedPreferences("m", MODE_PRIVATE).edit().putInt("flip", S.flip).apply()
                view.say(if (S.flip > 0) "pole as taught" else "pole flipped")
            })
        setContentView(view)
    }

    private fun zero(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val a = S.buf.filter { now - it.first < 1000 }
        if (a.size < 8 || S.same >= 15) return false
        S.b0 = FloatArray(3) { i -> a.sumOf { it.second[i].toDouble() }.toFloat() / a.size }
        S.res = floatArrayOf(0f, 0f, 0f); return true
    }

    override fun onResume() {
        super.onResume()
        val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val grv = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (mag != null) sm.registerListener(this, mag, 10000)
        if (grv != null) sm.registerListener(this, grv, 10000)
        view.start()
    }
    override fun onPause() { super.onPause(); sm.unregisterListener(this); view.stop() }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            val v = e.values
            val w = if (v.size > 3) v[3] else sqrt(max(0f, 1f - v[0]*v[0] - v[1]*v[1] - v[2]*v[2]))
            rotQ = floatArrayOf(v[0], v[1], v[2], w); return
        }
        if (e.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
        val now = SystemClock.elapsedRealtime()
        val m = floatArrayOf(e.values[0], e.values[1], e.values[2])
        val old = S.m
        if (old != null && old[0] == m[0] && old[1] == m[1] && old[2] == m[2]) S.same++ else S.same = 0
        S.m = m; S.q = rotQ; S.t = now
        S.buf.addLast(Pair(now, rot(rotQ, m, false)))
        while (S.buf.isNotEmpty() && now - S.buf.first().first > 1500) S.buf.removeFirst()
        val b0 = S.b0
        if (S.same < 15 && b0 != null) {
            val b = rot(rotQ, b0, true)
            S.res = floatArrayOf(m[0]-b[0], m[1]-b[1], m[2]-b[2])
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

private class Cmp(val kind: String, val rect: RectF) {
    var inf = 0f; var a = -1.5708f; var tx = 0f; var ty = 0f
    var outline: FloatArray? = null
    var press = 0f
}

class MetalView(ctx: Context, private val S: State, private val onZero: () -> Unit, private val onFlip: () -> Unit) :
    View(ctx), Choreographer.FrameCallback {

    private val dp = resources.displayMetrics.density
    private var running = false
    private var tPrev = SystemClock.elapsedRealtime()
    private val rs = floatArrayOf(0f, 0f, 0f)
    private var sAng = 0f; private var sLean = 0f
    private var px = 0f; private var py = 0f; private var pvx = 0f; private var pvy = 0f
    private var wob = 0f
    private var tSec = 0f
    private var auto = true
    private var demo = false
    private var touchX = -1f; private var touchY = -1f
    private var pressedCmp: Cmp? = null
    private var pressX = 0f; private var pressY = 0f
    private var segSel = 1
    private var toggleOn = true
    private var knobT = 1f
    private var sliderFrac = 0.62f
    private var style = 0  // preview: 0=reach 1=spikes 2=dents
    private var demoDownAt = 0L
    private var hint = ""; private var hintUntil = 0L
    private val cmps = mutableListOf<Cmp>()
    private var zeroRect = RectF(); private var flipRect = RectF(); private var demoRect = RectF()
    private var status = "starting"
    private val tmp = Path()

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL) }
    private val pDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(125, 130, 136) }

    fun say(t: String) { hint = t; hintUntil = SystemClock.elapsedRealtime() + 2500 }

    fun start() { if (!running) { running = true; tPrev = SystemClock.elapsedRealtime(); Choreographer.getInstance().postFrameCallback(this) } }
    fun stop() { running = false; Choreographer.getInstance().removeFrameCallback(this) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cmps.clear()
        val pad = 22 * dp
        val left = pad; val right = w - pad
        val headerH = 46 * dp; val footerH = 30 * dp
        fun row(height: Float, y: Float): RectF = RectF(left, y, right, y + height)
        // fixed row heights (dp), gaps distribute the leftover space evenly
        val heights = floatArrayOf(52f, 52f, 46f, 46f, 46f, 64f, 46f).map { it * dp }
        val total = heights.fold(0f) { acc, v -> acc + v }
        val avail = h - headerH - footerH - 8 * dp
        val gap = max(10 * dp, (avail - total) / (heights.size + 1))
        var y = headerH + gap
        // 1: email field + arrow ring
        run {
            val r = row(heights[0], y); val rb = 52 * dp
            cmps.add(Cmp("field", RectF(r.left, r.top, r.right - rb - 14 * dp, r.bottom)))
            cmps.add(Cmp("ring_arrow", RectF(r.right - rb, r.top, r.right, r.bottom)))
        }; y += heights[0] + gap
        // 2: continue / not now
        run {
            val r = row(heights[1], y); val half = (r.width() - 14 * dp) / 2
            cmps.add(Cmp("btn_primary", RectF(r.left, r.top, r.left + half, r.bottom)))
            cmps.add(Cmp("btn_ghost", RectF(r.left + half + 14 * dp, r.top, r.right, r.bottom)))
        }; y += heights[1] + gap
        // 3: segmented + toggle
        run {
            val r = row(heights[2], y); val tw = 58 * dp
            cmps.add(Cmp("seg", RectF(r.left, r.top, r.right - tw - 18 * dp, r.bottom)))
            cmps.add(Cmp("toggle", RectF(r.right - tw, r.top + (r.height() - 34 * dp) / 2, r.right, r.top + (r.height() + 34 * dp) / 2)))
        }; y += heights[2] + gap
        // 4: slider
        cmps.add(Cmp("slider", row(heights[3], y))); y += heights[3] + gap
        // 5: search
        cmps.add(Cmp("search", row(heights[4], y))); y += heights[4] + gap
        // 6: ring + chips
        run {
            val r = row(heights[5], y); val sz = 64 * dp
            cmps.add(Cmp("ring", RectF(r.left, r.top, r.left + sz, r.bottom)))
            val ch = 34 * dp; val cy = r.top + (r.height() - ch) / 2
            val cwid = 74 * dp
            cmps.add(Cmp("chip2", RectF(r.right - cwid, cy, r.right, cy + ch)))
            cmps.add(Cmp("chip1", RectF(r.right - 2 * cwid - 8 * dp, cy, r.right - cwid - 8 * dp, cy + ch)))
        }; y += heights[5] + gap
        // 7: icon rings
        run {
            val r = row(heights[6], y); val sz = 46 * dp; val gap2 = 22 * dp
            val total3 = 3 * sz + 2 * gap2; var x = r.left + (r.width() - total3) / 2
            for (k in 0..2) { cmps.add(Cmp("icon$k", RectF(x, r.top, x + sz, r.bottom))); x += sz + gap2 }
        }
        // footer touch zones
        val fy = h - footerH
        val bw = 74 * dp
        val hf = h.toFloat()
        zeroRect = RectF(w - pad - 3 * bw, fy, w - pad - 2 * bw, hf)
        flipRect = RectF(w - pad - 2 * bw, fy, w - pad - bw, hf)
        demoRect = RectF(w - pad - bw, fy, w - pad, hf)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (zeroRect.contains(e.x, e.y)) { onZero(); return true }
                if (flipRect.contains(e.x, e.y)) { onFlip(); return true }
                if (demoRect.contains(e.x, e.y)) { demoDownAt = SystemClock.elapsedRealtime(); return true }
                pressedCmp = cmps.firstOrNull { it.rect.contains(e.x, e.y) }
                pressedCmp?.let {
                    pressX = e.x; pressY = e.y
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (it.kind == "slider") sliderFrac = ((e.x - it.rect.left) / it.rect.width()).coerceIn(0.04f, 0.96f)
                    if (it.kind == "seg") segSel = (((e.x - it.rect.left) / it.rect.width()) * 3).toInt().coerceIn(0, 2)
                    return true
                }
                touchX = e.x; touchY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                pressedCmp?.let {
                    pressX = e.x; pressY = e.y
                    if (it.kind == "slider") sliderFrac = ((e.x - it.rect.left) / it.rect.width()).coerceIn(0.04f, 0.96f)
                    if (it.kind == "seg") segSel = (((e.x - it.rect.left) / it.rect.width()) * 3).toInt().coerceIn(0, 2)
                    return true
                }
                if (touchX >= 0) { touchX = e.x; touchY = e.y }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (demoDownAt > 0) {
                    val held = SystemClock.elapsedRealtime() - demoDownAt; demoDownAt = 0
                    if (e.actionMasked == MotionEvent.ACTION_UP && held > 600) {
                        style = (style + 1) % 3
                        say("style: " + arrayOf("reach", "spikes", "dents")[style])
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        demo = !demo; say(if (demo) "demo orbit on" else "demo orbit off")
                    }
                }
                pressedCmp?.let {
                    if (it.kind == "toggle") { toggleOn = !toggleOn; say(if (toggleOn) "on" else "off") }
                    pressedCmp = null
                }
                touchX = -1f; touchY = -1f
            }
        }
        return true
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        tSec = now / 1000f
        val dt = min(0.05f, (now - tPrev) / 1000f); tPrev = now
        step(now, dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun step(now: Long, dt: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        val R = min(w, h) * 0.52f
        val frozen = S.m == null || now - S.t > 1500 || S.same >= 15
        if (auto && S.b0 == null && !frozen && S.buf.size > 20 && now - S.buf.first().first > 1000) {
            if (zeroInternal()) { auto = false; say("auto-zeroed. keep magnets away, then use zero") }
        }
        var dx = 0f; var dy = 0f; var lean = 0f
        if (touchX >= 0) {
            val ddx = touchX - w / 2f; val ddy = h / 2f - touchY; val m = hypot(ddx, ddy).coerceAtLeast(1f)
            dx = ddx / m; dy = ddy / m; lean = min(1f, m / (min(w, h) * .5f))
        } else if (demo || (frozen && S.m == null && now - S.t > 4000)) {
            demo = true
            val t = now / 1000f
            dx = cos(t * 0.45f); dy = sin(t * 0.45f); lean = 0.55f + 0.35f * sin(t * 0.23f)
        } else if (!frozen && S.b0 != null) {
            val ka = 1f - exp(-dt / 0.15f); for (i in 0..2) rs[i] += (S.res[i] - rs[i]) * ka
            val r = floatArrayOf(rs[0] * S.flip, rs[1] * S.flip, rs[2] * S.flip)
            val mag = len3(r)
            if (mag > 2f) {
                val c = Classifier.classify(r)
                val th = Math.toRadians(c.ang.toDouble())
                dx = sin(th).toFloat(); dy = cos(th).toFloat()
                val confGate = ((c.conc - 0.4f) / 0.4f).coerceIn(0f, 1f)
                val fitGate = ((6f - c.fit) / 3f).coerceIn(0f, 1f)
                lean = (0.6f + 0.4f * ln(c.k) / ln(2f)).coerceIn(0.2f, 1f) * confGate * fitGate
            }
        }
        status = when {
            touchX >= 0 -> "touch demo"; demo -> "demo"; S.m == null -> "waiting for sensor"
            frozen -> "sensor frozen"; S.b0 != null -> "live · %.0f µT".format(len3(S.res)); else -> "zeroing"
        }
        val tl = lean
        if (hypot(dx, dy) > 0.1f && tl > 0.02f) {
            val ta = atan2(dx, dy)
            var da = ta - sAng; da = atan2(sin(da), cos(da))
            val wgt = min(1f, tl / 0.3f)
            val stp = da * (1f - exp(-dt * wgt / 0.3f))
            sAng += stp.coerceIn(-3.0f * dt, 3.0f * dt)
        }
        val tau = if (tl > sLean) 0.45f else 0.9f
        sLean += ((tl - sLean) * (1f - exp(-dt / tau))).coerceIn(-1.2f * dt, 1.2f * dt)
        val Tx = sin(sAng) * sLean; val Ty = cos(sAng) * sLean; val wn = 7f; val zt = 0.5f
        val n = max(1, ceil(dt / 0.008f).toInt()); val hh = dt / n
        for (i in 0 until n) {
            pvx += (wn * wn * (Tx - px) - 2 * zt * wn * pvx) * hh; pvy += (wn * wn * (Ty - py) - 2 * zt * wn * pvy) * hh
            px += pvx * hh; py += pvy * hh
        }
        val spd = min(1f, hypot(pvx, pvy) * 1.1f)
        wob += (spd - wob) * (1f - exp(-dt / (if (spd > wob) 0.08f else 0.7f)))

        knobT += (((if (toggleOn) 1f else 0f) - knobT) * (1f - exp(-dt / 0.15f)))
        val mx = w / 2 + px * R; val my = h / 2 - py * R
        val magAmt = min(1f, hypot(px, py))
        for (c in cmps) {
            val pT = if (c === pressedCmp) 1f else 0f
            c.press += (pT - c.press) * (1f - exp(-dt / 0.12f))
            val cx = c.rect.centerX(); val cy = c.rect.centerY()
            val vx = mx - cx; val vy = my - cy; val dist = hypot(vx, vy).coerceAtLeast(1f)
            val fall = ((520 * dp - dist) / (320 * dp)).coerceIn(0f, 1f)
            val infT = (magAmt * (0.22f + 0.78f * fall)).coerceIn(0f, 1f)
            val aT = atan2(vy, vx)
            val k = 1f - exp(-dt / 0.1f)
            c.inf += (infT - c.inf) * k
            var da = aT - c.a; da = atan2(sin(da), cos(da)); c.a += da * k
            val pull = 10 * dp * c.inf * (0.35f + 0.65f * fall)
            c.tx += (cos(aT) * pull - c.tx) * k; c.ty += (sin(aT) * pull - c.ty) * k
        }
    }

    private fun zeroInternal(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val a = S.buf.filter { now - it.first < 1000 }
        if (a.size < 8 || S.same >= 15) return false
        S.b0 = FloatArray(3) { i -> a.sumOf { it.second[i].toDouble() }.toFloat() / a.size }
        S.res = floatArrayOf(0f, 0f, 0f); return true
    }

    // ---------- drawing ----------

    private fun glowBehind(cv: Canvas, c: Cmp, rad: Float) {
        if (c.inf < 0.03f) return
        val ox = cos(c.a) * 14 * dp * c.inf; val oy = sin(c.a) * 14 * dp * c.inf
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; alpha = (0.10f * c.inf * 255).toInt()
            maskFilter = BlurMaskFilter(12 * dp, BlurMaskFilter.Blur.NORMAL)
        }
        cv.save(); cv.translate(ox, oy); cv.drawPath(deformed(c, rad), p); cv.restore()
    }

    private fun metalFill(cv: Canvas, c: Cmp, rad: Float, ghost: Boolean = false) {
        val c0 = c; val infSave = c.inf
        c.inf = (c.inf + 0.55f * c.press).coerceIn(0f, 1f)
        metalFillInner(cv, c, rad, ghost)
        c0.inf = infSave
    }

    private fun metalFillInner(cv: Canvas, c: Cmp, rad: Float, ghost: Boolean) {
        val r = c.rect
        val fx = cos(c.a); val fy = sin(c.a)
        val cx = r.centerX(); val cy = r.centerY()
        val L = max(r.width(), r.height()) / 2
        // base chrome, bright end facing the magnet
        val base = Paint(Paint.ANTI_ALIAS_FLAG)
        if (ghost) {
            base.shader = LinearGradient(cx - fx * L, cy - fy * L, cx + fx * L, cy + fy * L,
                intArrayOf(Color.rgb(10, 11, 12), Color.rgb(10, 11, 12)), null, Shader.TileMode.CLAMP)
        } else {
            base.shader = LinearGradient(cx + fx * L, cy + fy * L, cx - fx * L, cy - fy * L,
                intArrayOf(lerpColor(Color.rgb(23, 26, 29), Color.rgb(58, 64, 71), c.inf), Color.rgb(10, 11, 12)), null, Shader.TileMode.CLAMP)
        }
        val shape = deformed(c, rad)
        cv.drawPath(shape, base)
        // hotspot on the facing edge
        if (c.inf > 0.02f && !ghost) {
            val hx = cx + fx * r.width() * 0.58f * c.inf.coerceAtMost(1f)
            val hy = cy + fy * r.height() * 0.58f * c.inf.coerceAtMost(1f)
            val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(hx, hy, max(r.width(), r.height()) * 0.72f,
                    intArrayOf(withAlpha(Color.WHITE, 0.50f * c.inf), withAlpha(Color.WHITE, 0.10f * c.inf), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            }
            cv.save(); cv.clipPath(deformed(c, rad)); cv.drawRect(r, hp); cv.restore()
        }
        // rim: sweep brightest toward the magnet
        val bright = withAlpha(Color.WHITE, (if (ghost) 0.16f else 0.28f) + (if (ghost) 0.5f else 0.72f) * c.inf)
        val mid = withAlpha(Color.rgb(148, 154, 160), 0.34f + 0.18f * c.inf)
        val dim = withAlpha(Color.rgb(56, 60, 66), 0.38f)
        pStroke.shader = SweepGradient(cx, cy,
            intArrayOf(bright, mid, dim, dim, mid, bright),
            floatArrayOf(0f, 0.18f, 0.42f, 0.58f, 0.82f, 1f)).apply {
            val m = Matrix(); m.setRotate(Math.toDegrees(c.a.toDouble()).toFloat(), cx, cy); setLocalMatrix(m)
        }
        pStroke.strokeWidth = 1.2f * dp
        cv.drawPath(deformed(c, rad), pStroke)
        pStroke.shader = null
    }

    private fun knob(cv: Canvas, cx: Float, cy: Float, rad: Float, c: Cmp, boost: Float) {
        val fx = cos(c.a); val fy = sin(c.a)
        val hx = cx + fx * rad * 0.8f; val hy = cy + fy * rad * 0.8f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(hx, hy, rad * 2.1f,
                intArrayOf(lerpColor(Color.rgb(52, 58, 64), Color.rgb(235, 240, 245), (0.45f * c.inf * boost).coerceIn(0f, 1f)),
                    Color.rgb(34, 38, 42), Color.rgb(24, 27, 30)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        cv.drawCircle(cx, cy, rad, p)
        pStroke.shader = null; pStroke.strokeWidth = 1f * dp
        pStroke.color = withAlpha(Color.WHITE, 0.18f + 0.4f * c.inf)
        cv.drawCircle(cx, cy, rad - pStroke.strokeWidth / 2, pStroke)
    }

    private fun roundPath(r: RectF, rad: Float): Path {
        tmp.reset(); tmp.addRoundRect(r, rad, rad, Path.Direction.CW); return tmp
    }

    private fun baseOutline(c: Cmp, rad: Float): FloatArray {
        c.outline?.let { return it }
        val n = 72
        val pts = FloatArray(n * 2)
        val path = Path(); path.addRoundRect(c.rect, rad, rad, Path.Direction.CW)
        val pm = PathMeasure(path, true)
        val plen = pm.length; val pos = FloatArray(2)
        for (i in 0 until n) { pm.getPosTan(plen * i / n, pos, null); pts[i * 2] = pos[0]; pts[i * 2 + 1] = pos[1] }
        c.outline = pts; return pts
    }

    // ferrofluid boundary: facing edge reaches toward the magnet, far edge dimples in, small animated ripple
    private fun deformed(c: Cmp, rad: Float): Path {
        val pts = baseOutline(c, rad)
        val cx = c.rect.centerX(); val cy = c.rect.centerY()
        val dx = cos(c.a); val dy = sin(c.a)
        val cap = 0.30f * min(c.rect.width(), c.rect.height())
        val amp = min(13 * dp, cap) * c.inf
        val rip = 0.18f * min(13 * dp, cap) * c.inf * wob
        val sig2 = 2f * 0.60f * 0.60f
        val ph = tSec * 4f
        val pphi = if (c.press > 0.01f) atan2(pressY - cy, pressX - cx) else 0f
        val dipA = 8 * dp * c.press
        tmp.reset()
        val n = pts.size / 2
        for (i in 0 until n) {
            val x = pts[i * 2]; val y = pts[i * 2 + 1]
            val phi = atan2(y - cy, x - cx)
            var d = phi - c.a; d = atan2(sin(d), cos(d))
            var db = phi - (c.a + Math.PI.toFloat()); db = atan2(sin(db), cos(db))
            var disp = when (style) {
                1 -> { // spikes: three sharp lobes around the facing direction
                    var s = 1.25f * amp * exp(-d * d / (sig2 * 0.22f))
                    var d1 = d - 0.62f; d1 = atan2(sin(d1), cos(d1)); s += 0.65f * amp * exp(-d1 * d1 / (sig2 * 0.18f))
                    var d2 = d + 0.62f; d2 = atan2(sin(d2), cos(d2)); s += 0.65f * amp * exp(-d2 * d2 / (sig2 * 0.18f))
                    s - 0.30f * amp * exp(-db * db / sig2)
                }
                2 -> { // dents: travelling inward dents, slight swell toward the magnet
                    val travel = phi - ph * 0.6f
                    (0.35f * amp * exp(-d * d / sig2)
                        - amp * (0.55f + 0.45f * sin(2f * travel)) * exp(-d * d / (sig2 * 0.5f))
                        - 0.30f * amp * exp(-db * db / sig2))
                }
                else -> amp * exp(-d * d / sig2) - 0.38f * amp * exp(-db * db / sig2) // reach
            }
            if (rip != 0f) disp += (if (style == 1) 1.8f else 1f) * rip * sin(3f * phi + ph)
            var nx = x + dx * disp; var ny = y + dy * disp
            if (dipA > 0f) {
                var dd = phi - pphi; dd = atan2(sin(dd), cos(dd))
                val dip = dipA * exp(-dd * dd / (2f * 0.45f * 0.45f))
                val rl = hypot(x - cx, y - cy).coerceAtLeast(1f)
                nx -= (x - cx) / rl * dip; ny -= (y - cy) / rl * dip
            }
            if (i == 0) tmp.moveTo(nx, ny) else tmp.lineTo(nx, ny)
        }
        tmp.close()
        return tmp
    }

    override fun onDraw(cv: Canvas) {
        cv.drawColor(Color.BLACK)
        val w = width.toFloat(); val pad = 22 * dp
        // header
        pText.textSize = 13 * dp; pText.letterSpacing = 0.14f; pText.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        cv.drawText("M E T A L", pad, 26 * dp, pText)
        pText.letterSpacing = 0f; pText.typeface = Typeface.DEFAULT
        pDim.textSize = 10.5f * dp
        pDim.textAlign = Paint.Align.RIGHT
        cv.drawText(status + " · " + arrayOf("reach", "spikes", "dents")[style], w - pad, 25 * dp, pDim)
        pDim.textAlign = Paint.Align.LEFT

        for (c in cmps) {
            cv.save()
            cv.translate(c.tx, c.ty)
            val swell = 1f + 0.035f * c.inf - 0.030f * c.press
            cv.scale(swell, swell, c.rect.centerX(), c.rect.centerY())
            drawCmp(cv, c)
            cv.restore()
        }

        // footer
        pDim.textSize = 10.5f * dp
        if (SystemClock.elapsedRealtime() < hintUntil) cv.drawText(hint, pad, height - 10 * dp, pDim)
        pDim.textAlign = Paint.Align.CENTER
        cv.drawText("zero", zeroRect.centerX(), height - 10 * dp, pDim)
        cv.drawText("flip pole", flipRect.centerX(), height - 10 * dp, pDim)
        cv.drawText("demo", demoRect.centerX(), height - 10 * dp, pDim)
        pDim.textAlign = Paint.Align.LEFT
    }

    private fun drawCmp(cv: Canvas, c: Cmp) {
        val r = c.rect
        when (c.kind) {
            "field" -> {
                glowBehind(cv, c, r.height() / 2); metalFill(cv, c, r.height() / 2)
                pText.textSize = 15 * dp; pText.color = Color.rgb(201, 205, 209)
                cv.drawText("Your email", r.left + 20 * dp, r.centerY() + 5 * dp, pText)
                pText.color = Color.WHITE
            }
            "ring_arrow" -> {
                glowBehind(cv, c, r.height() / 2); metalFill(cv, c, r.height() / 2)
                drawArrow(cv, r.centerX(), r.centerY(), 9 * dp)
            }
            "btn_primary" -> {
                glowBehind(cv, c, r.height() / 2); metalFill(cv, c, r.height() / 2)
                pText.textSize = 15 * dp; pText.textAlign = Paint.Align.CENTER
                cv.drawText("Continue", r.centerX(), r.centerY() + 5 * dp, pText)
                pText.textAlign = Paint.Align.LEFT
            }
            "btn_ghost" -> {
                glowBehind(cv, c, r.height() / 2); metalFill(cv, c, r.height() / 2, ghost = true)
                pText.textSize = 15 * dp; pText.color = Color.rgb(201, 205, 209); pText.textAlign = Paint.Align.CENTER
                cv.drawText("Not now", r.centerX(), r.centerY() + 5 * dp, pText)
                pText.color = Color.WHITE; pText.textAlign = Paint.Align.LEFT
            }
            "seg" -> {
                glowBehind(cv, c, r.height() / 2)
                // active pill behind "Weekly"
                val segW = r.width() / 3
                val pr = RectF(r.left + segW * segSel + 3 * dp, r.top + 3 * dp, r.left + segW * (segSel + 1) - 3 * dp, r.bottom - 3 * dp)
                pr.offset(cos(c.a) * 5 * dp * c.inf, sin(c.a) * 2 * dp * c.inf)
                val pp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(pr.left, pr.top, pr.left, pr.bottom,
                        intArrayOf(lerpColor(Color.rgb(58, 64, 70), Color.rgb(96, 104, 112), c.inf), Color.rgb(29, 32, 36)),
                        null, Shader.TileMode.CLAMP)
                }
                cv.drawRoundRect(pr, pr.height() / 2, pr.height() / 2, pp)
                metalFill(cv, c, r.height() / 2)
                pText.textSize = 13 * dp; pText.textAlign = Paint.Align.CENTER
                val labels = arrayOf("Daily", "Weekly", "Yearly")
                for (i in 0..2) {
                    pText.color = if (i == segSel) Color.WHITE else Color.rgb(125, 130, 136)
                    cv.drawText(labels[i], r.left + segW * (i + 0.5f), r.centerY() + 4.5f * dp, pText)
                }
                pText.color = Color.WHITE; pText.textAlign = Paint.Align.LEFT
            }
            "toggle" -> {
                glowBehind(cv, c, r.height() / 2); metalFill(cv, c, r.height() / 2)
                val kx = r.left + r.height() / 2 + 1 * dp + (r.width() - r.height() - 2 * dp) * knobT + cos(c.a) * 6 * dp * c.inf
                knob(cv, kx, r.centerY(), 13 * dp, c, 1.1f)
            }
            "slider" -> {
                val cy = r.centerY(); val frac = sliderFrac
                pStroke.shader = null; pStroke.strokeWidth = 4 * dp; pStroke.strokeCap = Paint.Cap.ROUND
                pStroke.color = Color.rgb(38, 41, 45)
                cv.drawLine(r.left, cy, r.right, cy, pStroke)
                val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(r.left, cy, r.left + r.width() * frac, cy,
                        intArrayOf(Color.rgb(72, 79, 87), lerpColor(Color.rgb(60, 66, 73), Color.rgb(140, 148, 156), c.inf)),
                        null, Shader.TileMode.CLAMP)
                    strokeWidth = 4 * dp; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
                }
                cv.drawLine(r.left, cy, r.left + r.width() * frac, cy, fp)
                val tx = r.left + r.width() * frac + cos(c.a) * 8 * dp * c.inf
                val ty = cy + sin(c.a) * 8 * dp * c.inf
                knob(cv, tx, ty, 13 * dp, c, 1.2f)
            }
            "search" -> {
                glowBehind(cv, c, r.height() / 2); metalFill(cv, c, r.height() / 2)
                pDim.textSize = 14 * dp
                val ix = r.left + 18 * dp; val iy = r.centerY()
                pStroke.shader = null; pStroke.color = Color.rgb(125, 130, 136); pStroke.strokeWidth = 2 * dp
                cv.drawCircle(ix, iy - 2 * dp, 6 * dp, pStroke)
                cv.drawLine(ix + 4.5f * dp, iy + 2.5f * dp, ix + 9 * dp, iy + 7 * dp, pStroke)
                cv.drawText("Search", ix + 16 * dp, r.centerY() + 5 * dp, pDim)
            }
            "ring" -> {
                val cx = r.centerX(); val cy = r.centerY(); val rad = r.width() / 2 - 4 * dp
                pStroke.shader = null; pStroke.strokeWidth = 5 * dp; pStroke.strokeCap = Paint.Cap.ROUND
                pStroke.color = withAlpha(Color.WHITE, 0.12f)
                cv.drawCircle(cx, cy, rad, pStroke)
                val arc = RectF(cx - rad, cy - rad, cx + rad, cy + rad)
                pStroke.color = Color.rgb(232, 235, 238)
                cv.drawArc(arc, -90f, 244.8f, false, pStroke)   // 68%
                if (c.inf > 0.03f) {
                    pStroke.shader = SweepGradient(cx, cy,
                        intArrayOf(withAlpha(Color.WHITE, 0.55f * c.inf), Color.TRANSPARENT, Color.TRANSPARENT, withAlpha(Color.WHITE, 0.55f * c.inf)),
                        floatArrayOf(0f, 0.15f, 0.85f, 1f)).apply {
                        val m = Matrix(); m.setRotate(Math.toDegrees(c.a.toDouble()).toFloat(), cx, cy); setLocalMatrix(m)
                    }
                    cv.drawCircle(cx, cy, rad, pStroke); pStroke.shader = null
                }
                pDim.textSize = 13 * dp
                cv.drawText("68% sync", r.right + 16 * dp, cy + 4.5f * dp, pDim)
            }
            "chip1", "chip2" -> {
                glowBehind(cv, c, r.height() / 2); metalFill(cv, c, r.height() / 2)
                pText.textSize = 12.5f * dp; pText.color = Color.rgb(201, 205, 209); pText.textAlign = Paint.Align.CENTER
                cv.drawText(if (c.kind == "chip1") "Design" else "Motion", r.centerX(), r.centerY() + 4.5f * dp, pText)
                pText.color = Color.WHITE; pText.textAlign = Paint.Align.LEFT
            }
            "icon0", "icon1", "icon2" -> {
                glowBehind(cv, c, r.height() / 2); metalFill(cv, c, r.height() / 2)
                when (c.kind) {
                    "icon0" -> drawHeart(cv, r.centerX(), r.centerY(), 9 * dp)
                    "icon1" -> drawStar(cv, r.centerX(), r.centerY(), 9.5f * dp)
                    else -> drawUpload(cv, r.centerX(), r.centerY(), 9 * dp)
                }
            }
        }
    }

    private fun drawArrow(cv: Canvas, cx: Float, cy: Float, s: Float) {
        pStroke.shader = null; pStroke.color = Color.WHITE; pStroke.strokeWidth = 1.8f * dp; pStroke.strokeCap = Paint.Cap.ROUND
        cv.drawLine(cx - s, cy, cx + s * 0.8f, cy, pStroke)
        cv.drawLine(cx + s * 0.2f, cy - s * 0.7f, cx + s * 0.9f, cy, pStroke)
        cv.drawLine(cx + s * 0.2f, cy + s * 0.7f, cx + s * 0.9f, cy, pStroke)
    }

    private fun drawHeart(cv: Canvas, cx: Float, cy: Float, s: Float) {
        val p = Path()
        p.moveTo(cx, cy + s * 0.85f)
        p.cubicTo(cx - s * 1.6f, cy - s * 0.3f, cx - s * 0.7f, cy - s * 1.1f, cx, cy - s * 0.35f)
        p.cubicTo(cx + s * 0.7f, cy - s * 1.1f, cx + s * 1.6f, cy - s * 0.3f, cx, cy + s * 0.85f)
        pStroke.shader = null; pStroke.color = Color.WHITE; pStroke.strokeWidth = 1.6f * dp
        pStroke.strokeCap = Paint.Cap.ROUND; pStroke.strokeJoin = Paint.Join.ROUND
        cv.drawPath(p, pStroke)
    }

    private fun drawStar(cv: Canvas, cx: Float, cy: Float, s: Float) {
        val p = Path()
        for (i in 0..9) {
            val rad = if (i % 2 == 0) s else s * 0.42f
            val ang = -Math.PI / 2 + i * Math.PI / 5
            val x = cx + (rad * cos(ang)).toFloat(); val y = cy + (rad * sin(ang)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        pStroke.shader = null; pStroke.color = Color.WHITE; pStroke.strokeWidth = 1.6f * dp
        pStroke.strokeJoin = Paint.Join.ROUND
        cv.drawPath(p, pStroke)
    }

    private fun drawUpload(cv: Canvas, cx: Float, cy: Float, s: Float) {
        pStroke.shader = null; pStroke.color = Color.WHITE; pStroke.strokeWidth = 1.6f * dp
        pStroke.strokeCap = Paint.Cap.ROUND
        cv.drawLine(cx, cy + s * 0.8f, cx, cy - s * 0.8f, pStroke)
        cv.drawLine(cx - s * 0.5f, cy - s * 0.3f, cx, cy - s * 0.8f, pStroke)
        cv.drawLine(cx + s * 0.5f, cy - s * 0.3f, cx, cy - s * 0.8f, pStroke)
        cv.drawLine(cx - s * 0.8f, cy + s * 0.35f, cx - s * 0.8f, cy + s * 0.9f, pStroke)
        cv.drawLine(cx - s * 0.8f, cy + s * 0.9f, cx + s * 0.8f, cy + s * 0.9f, pStroke)
        cv.drawLine(cx + s * 0.8f, cy + s * 0.9f, cx + s * 0.8f, cy + s * 0.35f, pStroke)
    }

    private fun withAlpha(color: Int, a: Float): Int =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.argb(255,
            (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt())
    }
}
