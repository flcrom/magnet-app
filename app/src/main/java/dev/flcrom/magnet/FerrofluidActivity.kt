package dev.flcrom.magnet

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/**
 * Ferrofluid: a 1:1 GLES3 port of the react-bits Ferrofluid component
 * (github.com/DavidHDev/react-bits, src/content/Backgrounds/Ferrofluid/Ferrofluid.jsx).
 * The three shaders below are VERBATIM; only the ~200 line JS driver is ported to Kotlin.
 * Magnet residual (same State/Classifier pipeline as MainActivity) drives the cursor
 * crown; touch overrides as a demo cursor. Double-tap re-zeros the baseline.
 */
class FerrofluidActivity : Activity(), SensorEventListener {
    private val S = State()

    /** White ring at the live grid-model magnet estimate, drawn over the GL view.
     *  (The fading cyan trail was removed at the user's request; it lives on in the Slide train view.) */
    inner class TrailView(context: android.content.Context) : android.view.View(context) {
        private val pNow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.STROKE; strokeWidth = 5f }
        override fun onDraw(cv: android.graphics.Canvas) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - S.estT < 400 && S.estNX >= 0)
                cv.drawCircle(S.estNX * width, S.estNY * height, 18f, pNow)
            postInvalidateDelayed(50)
        }
    }
    private lateinit var trailV: TrailView
    private lateinit var sm: SensorManager
    private lateinit var gl: GLSurfaceView
    private var rotQ = floatArrayOf(0f, 0f, 0f, 1f)
    private var lastTap = 0L
    private lateinit var renderer: FerroRenderer

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gl = GLSurfaceView(this)
        gl.setEGLContextClientVersion(3)
        renderer = FerroRenderer(S, resources.displayMetrics.widthPixels.toFloat(), resources.displayMetrics.heightPixels.toFloat())
        gl.setRenderer(renderer)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        gl.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTap < 350) zero()
                    lastTap = now
                    S.touchX = e.x; S.touchY = e.y
                }
                MotionEvent.ACTION_MOVE -> { S.touchX = e.x; S.touchY = e.y }
                else -> { S.touchX = -1f; S.touchY = -1f }
            }
            true
        }
        setContentView(gl)
        trailV = TrailView(this)
        addContentView(trailV, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        trailV.postInvalidateDelayed(50)
    }

    private fun zero() {
        val now = SystemClock.elapsedRealtime()
        val a = S.buf.filter { now - it.first < 1000 }
        if (a.size < 8 || S.same >= 15) return
        S.b0 = FloatArray(3) { i -> a.sumOf { it.second[i].toDouble() }.toFloat() / a.size }
        S.res = floatArrayOf(0f, 0f, 0f)
    }

    override fun onResume() {
        super.onResume(); gl.onResume()
        renderer.grid = GridModel.load(filesDir)   // trained by CaptureActivity; null until he trains
        val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: return
        val grv = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        sm.registerListener(this, mag, 10000)
        if (grv != null) sm.registerListener(this, grv, 10000)
    }
    override fun onPause() { super.onPause(); gl.onPause(); sm.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            val v = e.values
            val w = if (v.size > 3) v[3] else sqrt(max(0f, 1f - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]))
            rotQ = floatArrayOf(v[0], v[1], v[2], w); return
        }
        if (e.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
        val now = SystemClock.elapsedRealtime()
        val m = floatArrayOf(e.values[0], e.values[1], e.values[2])
        val old = S.m
        if (old != null && old[0] == m[0] && old[1] == m[1] && old[2] == m[2]) S.same++ else S.same = 0
        S.m = m; S.q = rotQ; S.t = now; S.samples++
        S.buf.addLast(Pair(now, rot(rotQ, m, false)))
        while (S.buf.isNotEmpty() && now - S.buf.first().first > 1500) S.buf.removeFirst()
        val b0 = S.b0
        if (S.same < 15 && b0 != null) {
            val b = rot(rotQ, b0, true)
            S.res = floatArrayOf(m[0] - b[0], m[1] - b[1], m[2] - b[2])
            // slow re-zero: creep baseline toward current field when clearly no magnet
            // (kills sensor/environment drift; ~60s time constant, 1.2uT floor keeps weak hover signals)
            if (len3(S.res) < 1.2f) {
                val fix = rot(rotQ, S.res, false)
                val bf = rot(rotQ, b0, false)
                S.b0 = rot(rotQ, floatArrayOf(bf[0] + fix[0] * 0.0006f, bf[1] + fix[1] * 0.0006f, bf[2] + fix[2] * 0.0006f), true)
            }
        }
        // one-shot auto-zero ~1.2s after launch with fresh data
        if (S.b0 == null && S.same < 15 && S.buf.size > 20 && now - S.buf.first().first > 1000) zero()
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

class FerroRenderer(private val S: State, private val dispW: Float, private val dispH: Float) : GLSurfaceView.Renderer {
    @Volatile var grid: GridModel? = null
    // react-bits defaults (Ferrofluid.jsx)
    private val CELL = 1f / 3f; private val BOUND_CELL = 1f
    private val INTRO_SECONDS = 2.4f
    private val FOV = (38.0 * PI / 180.0).toFloat()
    private val SCALE = 1.6f; private val TILT = 48f
    private val SPEED = 0.5f
    private val SPIKE_HEIGHT = 1f; private val SHARPNESS = 2.5f
    private val TURBULENCE = 1f; private val FLUIDITY = 0.5f; private val MAGNETISM = 1f
    private val VARIATION = 0f
    private val FLOW = floatArrayOf(0f, -1f)   // down
    private val MOUSE_RADIUS = 0.22f; private val MOUSE_STRENGTH = 1f; private val MOUSE_DAMPENING = 0.15f

    private var simProg = 0; private var boundProg = 0; private var renderProg = 0
    private var vbo = 0
    private var floatTargets = false
    private var w = 1; private var h = 1
    private var worldHeight = 10f
    private var camX = 0f; private var camY = 0f; private var camZ = 1f
    private var fwd = floatArrayOf(0f, 1f, 0f); private var up = floatArrayOf(0f, 0f, 1f)
    private var far = 10f; private var camDist = 4f; private var reach = 1f
    private var gridW = 0; private var gridH = 0; private var gridOX = 0f; private var gridOY = 0f
    private var boundW = 0; private var boundH = 0
    private val tex = IntArray(2); private val fbo = IntArray(2)
    private var boundTex = 0; private var boundFbo = 0
    private var current = 0; private var settle = true
    private var wander = 0f; private var presence = 0f
    private var curX = 0f; private var curY = 0f; private var curStrength = 0f
    private var tPrev = 0L
    private var shapeRound = 0f; private var shapeExponent = 0f; private var shapeSpan = 0f
    private val tri = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)); position(0) }

    private fun sh(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val st = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) throw RuntimeException("shader: " + GLES30.glGetShaderInfoLog(s))
        return s
    }
    private fun link(fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, sh(GLES30.GL_VERTEX_SHADER, VERTEX))
        GLES30.glAttachShader(p, sh(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aPosition")
        GLES30.glLinkProgram(p)
        val st = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, st, 0)
        if (st[0] == 0) throw RuntimeException("link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }
    private fun u(p: Int, n: String) = GLES30.glGetUniformLocation(p, n)

    private fun makeTarget(tw: Int, th: Int, linear: Boolean, idx: Int) {
        val t = IntArray(1); val f = IntArray(1)
        GLES30.glGenTextures(1, t, 0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        if (floatTargets) GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, tw, th, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
        else GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, tw, th, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        val filt = if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filt)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filt)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glGenFramebuffers(1, f, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t[0], 0)
        GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        tex[idx] = t[0]; fbo[idx] = f[0]
    }

    private fun castRay(x: Float, y: Float): FloatArray {
        val tanHalf = tan(FOV / 2)
        val aspect = w.toFloat() / h.toFloat()
        val sx = x * aspect * tanHalf; val sy = y * tanHalf
        val dx = fwd[0] + sx; val dy = fwd[1] + sy * up[1]; val dz = fwd[2] + sy * up[2]
        val l = sqrt(dx * dx + dy * dy + dz * dz).takeIf { it > 0 } ?: 1f
        return floatArrayOf(dx / l, dy / l, dz / l)
    }
    private fun groundAt(x: Float, y: Float): FloatArray {
        val d = castRay(x, y)
        val t = if (d[2] < -0.004f) min(-camZ / d[2], far) else far
        return floatArrayOf(camX + d[0] * t, camY + d[1] * t)
    }

    private fun layout() {
        val tanHalf = tan(FOV / 2)
        worldHeight = 22f / SCALE
        val pitch = ((90f - TILT) * PI / 180.0).toFloat()
        val distance = worldHeight / (2 * tanHalf)
        fwd = floatArrayOf(0f, cos(pitch), -sin(pitch))
        up = floatArrayOf(0f, sin(pitch), cos(pitch))
        camX = 0f; camY = -distance * cos(pitch); camZ = distance * sin(pitch)
        far = distance * 2.5f; camDist = distance
        val corners = arrayOf(groundAt(-1f, -1f), groundAt(1f, -1f), groundAt(-1f, 1f), groundAt(1f, 1f), groundAt(0f, 1f))
        val minX = corners.minOf { it[0] } - 3; val maxX = corners.maxOf { it[0] } + 3
        val minY = corners.minOf { it[1] } - 3; val maxY = corners.maxOf { it[1] } + 3
        reach = corners.maxOf { hypot(it[0], it[1]) } + 4
        val gw = ceil((maxX - minX) / CELL).toInt() + 2
        val gh = ceil((maxY - minY) / CELL).toInt() + 2
        gridOX = minX; gridOY = minY
        if (gw != gridW || gh != gridH) {
            gridW = gw; gridH = gh
            boundW = ceil(gridW * CELL / BOUND_CELL).toInt(); boundH = ceil(gridH * CELL / BOUND_CELL).toInt()
            makeTarget(gridW, gridH, true, 0); makeTarget(gridW, gridH, true, 1)
            val t = IntArray(1); val f = IntArray(1)
            GLES30.glGenTextures(1, t, 0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
            if (floatTargets) GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, boundW, boundH, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            else GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, boundW, boundH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glGenFramebuffers(1, f, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t[0], 0)
            GLES30.glClearColor(0f, 0f, 0f, 0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            boundTex = t[0]; boundFbo = f[0]
            current = 0
        }
        settle = true
    }

    override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {
        val exts = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        floatTargets = exts.contains("EXT_color_buffer_float")
        simProg = link(SIM_FRAGMENT); boundProg = link(BOUND_FRAGMENT); renderProg = link(RENDER_FRAGMENT)
        val b = IntArray(1); GLES30.glGenBuffers(1, b, 0); vbo = b[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 24, tri, GLES30.GL_STATIC_DRAW)
        val edge = ((SHARPNESS - 1) / 4).coerceIn(0f, 1f)
        shapeRound = 1 - edge * 0.7f
        shapeExponent = 1.05f + edge * 1.2f
        shapeSpan = sqrt(1 + shapeRound * shapeRound) - shapeRound
        tPrev = 0L
    }
    override fun onSurfaceChanged(g: GL10?, W: Int, H: Int) { w = W; h = H; layout() }

    override fun onDrawFrame(g: GL10?) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (tPrev == 0L) 1f / 60f else ((now - tPrev) / 1000f).coerceIn(0.0005f, 0.05f)
        tPrev = now

        // --- step(): cursor from touch or magnet ---
        wander += dt * SPEED
        presence = min(1f, presence + dt / INTRO_SECONDS)
        var engaged = false
        var px = 0f; var py = 0f; var targetStrength = 0f
        if (S.touchX >= 0) {
            engaged = true
            val gx = (S.touchX / max(1f, w.toFloat())) * 2 - 1
            val gy = 1 - (S.touchY / max(1f, h.toFloat())) * 2
            val gp = groundAt(gx, gy); px = gp[0]; py = gp[1]
            targetStrength = MOUSE_STRENGTH * 1.4f
        } else {
            val frozen = S.m == null || now - S.t > 1500   // identical readings are a PARKED magnet, not a freeze; only a stalled event stream is frozen
            val b0 = S.b0
            if (!frozen && b0 != null) {
                val mag = len3(S.res)
                val sens = 0.5f + S.sens / 50f
                if (mag > 3f / sens) {
                    val resF = floatArrayOf(S.res[0] * S.flip, S.res[1] * S.flip, S.res[2] * S.flip)
                    val est = grid?.estimate(resF)
                    if (est != null && est.conf > 0.12f) {
                        // trained grid: magnet position maps straight to a screen point.
                        // Low confidence: FREEZE at the last confident position instead of jumping.
                        engaged = true
                        val sure = est.conf > 0.4f || S.estT == 0L
                        val enx = if (sure) est.nx else S.estNX
                        val eny = if (sure) est.ny else S.estNY
                        val gp = groundAt(enx * 2 - 1, 1 - eny * 2)
                        px = gp[0]; py = gp[1]
                        val cf = (est.conf / 0.4f).coerceIn(0.25f, 1f)   // unsure = weak eruption
                        targetStrength = MOUSE_STRENGTH * 1.4f * est.k.coerceIn(0f, 1.2f) * cf   // k: 1 = on-glass, <1 = hovering
                        if (sure) { S.estNX = est.nx; S.estNY = est.ny; S.estK = est.k; S.estT = now }
                    } else {
                    val cl = Classifier.classify(resF)
                    val confGate = ((cl.conc - 0.4f) / 0.4f).coerceIn(0f, 1f)
                    val fitGate = ((6f - cl.fit) / 3f).coerceIn(0f, 1f)
                    val lean = (0.6f + 0.4f * ln(cl.k) / ln(2f)).coerceIn(0.2f, 1f) * confGate * fitGate
                    if (lean > 0.02f) {
                        engaged = true
                        val th = Math.toRadians(cl.ang.toDouble())
                        val ring = 0.22f * worldHeight * min(1f, w.toFloat() / h.toFloat() * 2f)
                        px = sin(th).toFloat() * ring * lean
                        py = cos(th).toFloat() * ring * lean
                        targetStrength = MOUSE_STRENGTH * 1.4f * (lean * cl.k).coerceIn(0f, 1.2f)
                    }
                    }
                }
            }
        }
        val follow = if (MOUSE_DAMPENING > 0) 1 - exp(-dt / MOUSE_DAMPENING) else 1f
        if (engaged) { curX += (px - curX) * follow; curY += (py - curY) * follow }
        curStrength += (targetStrength - curStrength) * (1 - exp(-dt / 0.35f))
        if (abs(targetStrength - curStrength) < 0.001f) curStrength = targetStrength

        // --- draw(): sim, bound, render (verbatim sequence from Ferrofluid.jsx) ---
        val radius = MOUSE_RADIUS * worldHeight
        val fullHeight = 1.2f * SPIKE_HEIGHT
        val reveal = presence * presence * (3 - 2 * presence)
        val read = current; val write = 1 - current

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[write])
        GLES30.glViewport(0, 0, gridW, gridH)
        GLES30.glUseProgram(simProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[read])
        GLES30.glUniform1i(u(simProg, "uState"), 0)
        GLES30.glUniform2f(u(simProg, "uGridOrigin"), gridOX, gridOY)
        GLES30.glUniform1f(u(simProg, "uCell"), CELL)
        GLES30.glUniform1f(u(simProg, "uDt"), max(dt, 0.001f))
        GLES30.glUniform1f(u(simProg, "uTime"), wander)
        GLES30.glUniform2f(u(simProg, "uFlow"), FLOW[0], FLOW[1])
        GLES30.glUniform1f(u(simProg, "uTurbulence"), TURBULENCE)
        GLES30.glUniform1f(u(simProg, "uMagnetism"), 0f)   // user: calm unless magnet - no ambient swells
        GLES30.glUniform1f(u(simProg, "uFieldScale"), 5.5f)
        GLES30.glUniform2f(u(simProg, "uSeed"), VARIATION * 17.31f, VARIATION * 9.73f)
        GLES30.glUniform4f(u(simProg, "uCursor"), curX, curY, curStrength, radius)
        GLES30.glUniform4f(u(simProg, "uPulse"), 0f, 0f, 10f, 0f)
        GLES30.glUniform1f(u(simProg, "uPulseSpeed"), worldHeight * 0.45f)
        GLES30.glUniform1f(u(simProg, "uPulseWidth"), 1.4f)
        GLES30.glUniform3f(u(simProg, "uIntro"), 0f, 0f, reveal * (reach + 3))
        GLES30.glUniform1f(u(simProg, "uSpikeHeight"), fullHeight)
        GLES30.glUniform1f(u(simProg, "uMound"), 0.6f)
        GLES30.glUniform1f(u(simProg, "uStiffness"), 150f)
        GLES30.glUniform1f(u(simProg, "uDamping"), 22 - FLUIDITY * 15)
        GLES30.glUniform1f(u(simProg, "uSettle"), if (settle || !floatTargets) 1f else 0f)
        drawTri(simProg)
        settle = false
        current = write

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, boundFbo)
        GLES30.glViewport(0, 0, boundW, boundH)
        GLES30.glUseProgram(boundProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[write])
        GLES30.glUniform1i(u(boundProg, "uState"), 0)
        GLES30.glUniform1f(u(boundProg, "uCell"), CELL)
        GLES30.glUniform2f(u(boundProg, "uStateSize"), gridW.toFloat(), gridH.toFloat())
        GLES30.glUniform1f(u(boundProg, "uBoundCell"), BOUND_CELL)
        drawTri(boundProg)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(renderProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[write])
        GLES30.glUniform1i(u(renderProg, "uField"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, boundTex)
        GLES30.glUniform1i(u(renderProg, "uBound"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform2f(u(renderProg, "uGridOrigin"), gridOX, gridOY)
        GLES30.glUniform2f(u(renderProg, "uGridSpan"), gridW * CELL, gridH * CELL)
        GLES30.glUniform2f(u(renderProg, "uBoundSpan"), boundW * BOUND_CELL, boundH * BOUND_CELL)
        GLES30.glUniform1f(u(renderProg, "uCellSize"), CELL)
        GLES30.glUniform2f(u(renderProg, "uResolution"), w.toFloat(), h.toFloat())
        GLES30.glUniform3f(u(renderProg, "uCamera"), camX, camY, camZ)
        GLES30.glUniform3f(u(renderProg, "uForward"), fwd[0], fwd[1], fwd[2])
        GLES30.glUniform3f(u(renderProg, "uUpward"), up[0], up[1], up[2])
        GLES30.glUniform1f(u(renderProg, "uTanHalf"), tan(FOV / 2))
        GLES30.glUniform1f(u(renderProg, "uPixelAngle"), (2 * tan(FOV / 2)) / h)
        GLES30.glUniform1f(u(renderProg, "uFar"), far)
        GLES30.glUniform1f(u(renderProg, "uFogStart"), camDist * 0.95f)
        GLES30.glUniform1f(u(renderProg, "uTop"), fullHeight * 1.6f + 0.6f * 3 + 0.1f)
        GLES30.glUniform1f(u(renderProg, "uBottom"), -0.05f)
        GLES30.glUniform1f(u(renderProg, "uTime"), wander * 2)
        GLES30.glUniform4f(u(renderProg, "uCursor"), curX, curY, curStrength, radius)
        GLES30.glUniform1f(u(renderProg, "uMound"), 0.6f)
        GLES30.glUniform1f(u(renderProg, "uRound"), shapeRound)
        GLES30.glUniform1f(u(renderProg, "uSpan"), shapeSpan)
        GLES30.glUniform1f(u(renderProg, "uExponent"), shapeExponent)
        GLES30.glUniform1f(u(renderProg, "uWarp"), 0.06f * TURBULENCE)
        GLES30.glUniform3f(u(renderProg, "uKey"), 1f, 1f, 1f)
        GLES30.glUniform3f(u(renderProg, "uStrip"), 1f, 1f, 1f)
        GLES30.glUniform3f(u(renderProg, "uFill"), 1f, 1f, 1f)
        GLES30.glUniform1f(u(renderProg, "uExposure"), 3f)
        GLES30.glUniform1f(u(renderProg, "uLight"), 0f)
        GLES30.glUniform1f(u(renderProg, "uOpaque"), 1f)
        GLES30.glUniform3f(u(renderProg, "uBase"), 0f, 0f, 0f)
        GLES30.glUniform1f(u(renderProg, "uOpacity"), 1f)
        drawTri(renderProg)
    }

    private fun drawTri(prog: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    companion object {
        const val VERTEX = """#version 300 es
in vec2 aPosition;
void main() {
  gl_Position = vec4(aPosition, 0.0, 1.0);
}"""
        const val SIM_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uState;
uniform vec2 uGridOrigin;
uniform float uCell;
uniform float uDt;
uniform float uTime;
uniform vec2 uFlow;
uniform float uTurbulence;
uniform float uMagnetism;
uniform float uFieldScale;
uniform vec2 uSeed;
uniform vec4 uCursor;
uniform vec4 uPulse;
uniform float uPulseSpeed;
uniform float uPulseWidth;
uniform vec3 uIntro;
uniform float uSpikeHeight;
uniform float uMound;
uniform float uStiffness;
uniform float uDamping;
uniform float uSettle;
out vec4 outState;

float hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

float noise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  float a = hash(i);
  float b = hash(i + vec2(1.0, 0.0));
  float c = hash(i + vec2(0.0, 1.0));
  float d = hash(i + vec2(1.0, 1.0));
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float flowField(vec2 p) {
  vec2 q = p / uFieldScale + uSeed;
  vec2 drift = uFlow * uTime * 0.55;
  vec2 warp = vec2(noise(q * 0.6 + vec2(uTime * 0.07, 3.1)), noise(q * 0.6 - vec2(7.3, uTime * 0.06))) - 0.5;
  vec2 r = q + warp * 1.8 * uTurbulence - drift;
  float n = noise(r) * 0.65 + noise(r * 2.03 + 11.7 - drift * 0.5) * 0.35;
  return smoothstep(0.44, 0.86, n);
}

void main() {
  vec2 texel = floor(gl_FragCoord.xy);
  vec2 p = uGridOrigin + (texel + 0.5) * uCell;
  vec4 prev = texelFetch(uState, ivec2(texel), 0);
  float swell = flowField(p);
  float field = uMagnetism * (0.03 + 1.0 * swell * swell);
  vec2 dc = p - uCursor.xy;
  float falloff = dot(dc, dc) / (uCursor.w * uCursor.w);
  field += uCursor.z * exp(-falloff);
  float mound = uCursor.z * exp(-falloff / 2.2);
  if (uPulse.w > 0.0) {
    float ring = length(p - uPulse.xy) - uPulse.z * uPulseSpeed;
    float envelope = smoothstep(0.0, 0.08, uPulse.z) * exp(-uPulse.z * 1.1);
    field += uPulse.w * envelope * exp(-ring * ring / (uPulseWidth * uPulseWidth));
  }
  float front = clamp((uIntro.z - length(p - uIntro.xy)) / 3.0, 0.0, 1.0);
  front = front * front * (3.0 - 2.0 * front);
  float target = uSpikeHeight * min(pow(max(field * front - 0.04, 0.0), 1.6), 1.0);
  float height = prev.x;
  float velocity = prev.y;
  if (uSettle > 0.5) {
    height = target;
    velocity = 0.0;
  } else {
    float steps = ceil(uDt / 0.008);
    float h = uDt / steps;
    for (int k = 0; k < 8; k++) {
      if (float(k) >= steps) break;
      velocity += (uStiffness * (target - height) - uDamping * velocity) * h;
      height += velocity * h;
    }
    if (height < 0.0) {
      height = 0.0;
      velocity = max(velocity, 0.0);
    }
  }
  outState = vec4(height, velocity, mound * front * uMound, 1.0);
}"""
        const val BOUND_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uState;
uniform vec2 uGridOrigin;
uniform float uCell;
uniform vec2 uStateSize;
uniform float uBoundCell;
out vec4 outBound;

void main() {
  vec2 center = uGridOrigin + (floor(gl_FragCoord.xy) + 0.5) * uBoundCell;
  vec2 base = floor((center - uGridOrigin) / uCell);
  float top = 0.0;
  float peak = 0.0;
  for (int y = -4; y <= 4; y++) {
    for (int x = -4; x <= 4; x++) {
      vec2 texel = clamp(base + vec2(float(x), float(y)), vec2(0.0), uStateSize - 1.0);
      vec4 s = texelFetch(uState, ivec2(texel), 0);
      top = max(top, max(s.x, 0.0) * 1.05 + s.z);
      peak = max(peak, s.x);
    }
  }
  outBound = vec4(top, peak, 0.0, 1.0);
}"""
        const val RENDER_FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uField;
uniform sampler2D uBound;
uniform vec2 uGridOrigin;
uniform vec2 uGridSpan;
uniform vec2 uBoundSpan;
uniform float uCellSize;
uniform vec2 uResolution;
uniform vec3 uCamera;
uniform vec3 uForward;
uniform vec3 uUpward;
uniform float uTanHalf;
uniform float uPixelAngle;
uniform float uFar;
uniform float uFogStart;
uniform float uTop;
uniform float uBottom;
uniform float uTime;
uniform vec4 uCursor;
uniform float uMound;
uniform float uRound;
uniform float uSpan;
uniform float uExponent;
uniform float uWarp;
uniform vec3 uKey;
uniform vec3 uStrip;
uniform vec3 uFill;
uniform float uExposure;
uniform float uLight;
uniform float uOpaque;
uniform vec3 uBase;
uniform float uOpacity;
out vec4 outColor;

const float G = 7.2551975;
const vec2 E1 = vec2(0.8660254, 0.5);
const vec2 E2 = vec2(-0.8660254, 0.5);
const vec2 E3 = vec2(0.0, -1.0);
const mat2 TURN = mat2(0.9902681, 0.1391731, -0.1391731, 0.9902681);
const float F0 = 0.06;

vec3 mound(vec2 p) {
  vec2 d = p - uCursor.xy;
  float inv = 1.0 / (uCursor.w * uCursor.w * 2.2);
  float e = uCursor.z * exp(-dot(d, d) * inv);
  return vec3(e, -2.0 * inv * e * d) * uMound;
}

vec2 lattice(vec2 p) {
  vec2 q = TURN * p;
  float t = uTime * 0.35;
  return q + uWarp * vec2(sin(q.y * 0.9 + t), cos(q.x * 0.8 - t * 0.8));
}

float detail(float t) {
  return smoothstep(3.0, 8.0, 1.0 / max(t * uPixelAngle, 1e-5)) * (1.0 - smoothstep(uFogStart, mix(uFogStart, uFar, 0.7), t));
}

float profile(float u, out float rate) {
  float root = sqrt(max(1.0 - u, 0.0) + uRound * uRound);
  rate = 0.5 / (uSpan * root);
  return clamp(1.0 - (root - uRound) / uSpan, 0.0, 1.0);
}

float spikes(vec2 p, float a, float rise, out float clearance) {
  clearance = 10.0;
  if (a < 0.002) return 0.0;
  vec2 q = lattice(p);
  float u = (cos(G * dot(E1, q)) + cos(G * dot(E2, q)) + cos(G * dot(E3, q)) + 1.5) / 4.5;
  if (a > 0.05) {
    float root = uRound + (1.0 - pow(clamp(rise / a, 0.0, 1.0), 1.0 / uExponent)) * uSpan;
    clearance = sqrt(max(1.0 - u, 0.0) / 8.77) - sqrt(max(root * root - uRound * uRound, 0.0) / 8.77);
  }
  float rate;
  return a * pow(profile(u, rate), uExponent);
}

vec2 spikesSlope(vec2 p, float a, vec2 aSlope) {
  if (a < 0.002) return vec2(0.0);
  vec2 q = lattice(p);
  float c1 = G * dot(E1, q);
  float c2 = G * dot(E2, q);
  float c3 = G * dot(E3, q);
  float u = (cos(c1) + cos(c2) + cos(c3) + 1.5) / 4.5;
  vec2 du = -(G / 4.5) * (sin(c1) * E1 + sin(c2) * E2 + sin(c3) * E3) * TURN;
  float rate;
  float s = max(profile(u, rate), 1e-4);
  float shape = pow(s, uExponent);
  return a * uExponent * shape / s * rate * du + aSlope * shape;
}

float surface(vec2 p, float z, float t, out float clearance) {
  vec4 f = texture(uField, (p - uGridOrigin) / uGridSpan);
  return f.z + spikes(p, f.x * detail(t), z - f.z, clearance);
}

vec2 bound(vec2 p) {
  return texture(uBound, (p - uGridOrigin) / uBoundSpan).rg;
}

float panel(vec3 r, vec3 dir, vec3 up, vec2 extent, float soft) {
  vec3 side = normalize(cross(dir, up));
  vec3 top = cross(side, dir);
  float facing = dot(r, dir);
  if (facing <= 0.0) return 0.0;
  vec2 q = vec2(dot(r, side), dot(r, top)) / facing;
  vec2 e = smoothstep(extent + soft, extent - soft, abs(q));
  return e.x * e.y;
}

vec3 studio(vec3 r, vec3 rd, float soft) {
  vec3 z = vec3(0.0, 0.0, 1.0);
  vec3 col = uKey * 4.2 * panel(r, normalize(vec3(-0.3, -0.5, 0.81)), z, vec2(0.36, 0.22), 0.08 + soft);
  col += uStrip * 1.5 * panel(r, normalize(vec3(-0.9, -0.2, 0.38)), z, vec2(0.12, 0.7), 0.07 + soft);
  col += uStrip * 1.5 * panel(r, normalize(vec3(0.9, -0.1, 0.42)), z, vec2(0.12, 0.7), 0.07 + soft);
  float lift = asin(clamp(r.z, -1.0, 1.0)) - asin(clamp(-rd.z, -1.0, 1.0));
  col += uFill * 0.45 * smoothstep(0.08 - soft, 0.7 + soft, lift) * smoothstep(1.6, 1.0, lift);
  return col;
}

vec4 finish(vec3 light, vec3 paper, float alpha) {
  if (uLight > 0.5) {
    if (uOpaque > 0.5) return vec4(mix(uBase, paper, alpha) * uOpacity, uOpacity);
    vec3 shade = mix(vec3(1.0), paper, alpha * uOpacity);
    float cover = 1.0 - min(shade.r, min(shade.g, shade.b));
    return vec4(shade - (1.0 - cover), cover);
  }
  vec3 glow = light * alpha * uOpacity;
  if (uOpaque > 0.5) return vec4(uBase + glow, 1.0);
  return vec4(glow, max(glow.r, max(glow.g, glow.b)));
}

void main() {
  vec2 ndc = (gl_FragCoord.xy - 0.5 * uResolution) / (0.5 * uResolution.y);
  vec3 side = normalize(cross(uForward, uUpward));
  vec3 rd = normalize(uForward + (ndc.x * side + ndc.y * uUpward) * uTanHalf);
  if (rd.z > -0.004) {
    outColor = finish(vec3(0.0), vec3(1.0), 0.0);
    return;
  }
  float tTop = max((uTop - uCamera.z) / rd.z, 0.0);
  float tEnd = min((uBottom - uCamera.z) / rd.z, uFar);
  if (tTop >= tEnd) {
    outColor = finish(vec3(0.0), vec3(1.0), 0.0);
    return;
  }
  float sink = max(-rd.z, 0.2);
  float glide = max(length(rd.xy), 0.05);
  float chunk = 0.9 / glide;
  float t = tEnd;
  float tPrev = tEnd;
  bool found = false;
  bool smoothHit = false;
  float t0 = tTop;
  vec2 b0 = bound(uCamera.xy + rd.xy * t0);
  for (int c = 0; c < 64; c++) {
    if (t0 >= tEnd) break;
    float t1 = min(t0 + chunk, tEnd);
    vec2 b1 = bound(uCamera.xy + rd.xy * t1);
    float b = max(b0.x, b1.x) + 0.03;
    float z1 = uCamera.z + rd.z * t1;
    if (z1 > b) {
      t0 = t1;
      b0 = b1;
      continue;
    }
    float tt = max(t0, (b - uCamera.z) / rd.z);
    if (max(b0.y, b1.y) < 0.002) {
      for (int k = 0; k < 3; k++) {
        vec3 probe = uCamera + rd * tt;
        tt += (probe.z - mound(probe.xy).x) / sink * 0.95;
      }
      if (tt <= t1 + 0.02 || t1 >= tEnd) {
        t = tt;
        smoothHit = true;
        break;
      }
      t0 = t1;
      b0 = b1;
      continue;
    }
    float tp = tt;
    for (int k = 0; k < 48; k++) {
      vec3 pos = uCamera + rd * tt;
      float clearance;
      float gap = pos.z - surface(pos.xy, pos.z, tt, clearance);
      if (gap < 0.0) {
        found = true;
        break;
      }
      tp = tt;
      float pixel = tt * uPixelAngle;
      float limit = pos.z > bound(pos.xy).x + 0.02 ? 0.6 / glide : max(clearance * 0.9, pixel) / glide;
      tt += max(min(gap * 0.75 / sink, limit), pixel);
      if (tt > t1) break;
    }
    if (found) {
      t = tt;
      tPrev = tp;
      break;
    }
    t0 = t1;
    b0 = b1;
  }

  vec3 pos;
  vec2 slope;
  if (smoothHit || !found) {
    pos = uCamera + rd * t;
    slope = mound(pos.xy).yz;
  } else {
    float lo = tPrev;
    float hi = t;
    for (int k = 0; k < 4; k++) {
      float mid = 0.5 * (lo + hi);
      vec3 probe = uCamera + rd * mid;
      float clearance;
      if (probe.z - surface(probe.xy, probe.z, mid, clearance) < 0.0) hi = mid;
      else lo = mid;
    }
    t = hi;
    pos = uCamera + rd * t;
    vec2 uv = (pos.xy - uGridOrigin) / uGridSpan;
    vec2 step = uCellSize / uGridSpan;
    float fade = detail(t);
    float a = texture(uField, uv).x * fade;
    vec2 aSlope = vec2(
      texture(uField, uv + vec2(step.x, 0.0)).x - texture(uField, uv - vec2(step.x, 0.0)).x,
      texture(uField, uv + vec2(0.0, step.y)).x - texture(uField, uv - vec2(0.0, step.y)).x
    ) * fade / (2.0 * uCellSize);
    slope = mound(pos.xy).yz + spikesSlope(pos.xy, a, aSlope);
  }

  vec3 n = normalize(vec3(-slope, 1.0));
  vec3 r = reflect(rd, n);
  float soft = length(fwidth(r)) * 0.6;
  float facing = max(dot(n, -rd), 0.0);
  float fresnel = F0 + (1.0 - F0) * pow(1.0 - facing, 5.0);
  float rim = pow(1.0 - clamp(abs(dot(rd, n)), 0.0, 1.0), 3.0) * smoothstep(0.85, 0.4, n.z);
  float distance = 1.0 - smoothstep(uFogStart, uFar, t);

  if (uLight > 0.5) {
    vec3 key = normalize(vec3(-0.45, -0.55, 0.7));
    float lambert = clamp(dot(n, key) / key.z, 0.0, 1.0);
    float diffuse = mix(0.62, 1.0, lambert);
    float room = mix(0.3, 1.0, smoothstep(-0.25, 0.3, r.z));
    room -= 0.3 * rim;
    float lum = mix(diffuse, room, fresnel);
    lum = 1.0 - (1.0 - lum) * clamp(uExposure / 3.0, 0.0, 1.5);
    vec3 shadow = mix(vec3(0.0), uStrip, 0.45);
    vec3 paper = mix(shadow, vec3(1.0), clamp(lum, 0.0, 1.0));
    outColor = finish(vec3(0.0), paper, distance);
    return;
  }

  vec3 shine = 1.0 - exp(-(studio(r, rd, soft) * fresnel + uFill * rim * 0.35) * uExposure);
  outColor = finish(shine, vec3(1.0), distance);
}"""
    }
}

