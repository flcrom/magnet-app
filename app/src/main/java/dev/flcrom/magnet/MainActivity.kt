package dev.flcrom.magnet

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

/** Sensor state shared between the sensor callback (main thread) and the renderer. */
class State {
    @Volatile var m: FloatArray? = null
    @Volatile var q = floatArrayOf(0f, 0f, 0f, 1f)
    @Volatile var t = 0L
    @Volatile var same = 0
    @Volatile var b0: FloatArray? = null
    @Volatile var res = floatArrayOf(0f, 0f, 0f)
    @Volatile var flip = 1
    @Volatile var sens = 50
    @Volatile var auto = true
    @Volatile var touchX = -1f
    @Volatile var touchY = -1f
    @Volatile var samples = 0L
    val buf = ArrayDeque<Pair<Long, FloatArray>>()   // (time, field in fixed frame)

    // UI readout
    @Volatile var status = "starting"
    @Volatile var readout = ""
    @Volatile var live = false
}

fun rot(q: FloatArray, v: FloatArray, inv: Boolean): FloatArray {
    var x = q[0]; var y = q[1]; var z = q[2]; val w = q[3]
    if (inv) { x = -x; y = -y; z = -z }
    val tx = 2 * (y * v[2] - z * v[1]); val ty = 2 * (z * v[0] - x * v[2]); val tz = 2 * (x * v[1] - y * v[0])
    return floatArrayOf(v[0] + w * tx + (y * tz - z * ty), v[1] + w * ty + (z * tx - x * tz), v[2] + w * tz + (x * ty - y * tx))
}
fun len3(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

class MainActivity : Activity(), SensorEventListener {
    private val S = State()
    private lateinit var sm: SensorManager
    private lateinit var gl: GLSurfaceView
    private lateinit var statusTv: TextView
    private lateinit var readTv: TextView
    private lateinit var hintTv: TextView
    private var rotQ = floatArrayOf(0f, 0f, 0f, 1f)
    private val ui = Handler(Looper.getMainLooper())
    private var fullscreen = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val prefs = getSharedPreferences("m", MODE_PRIVATE)
        S.flip = prefs.getInt("flip", 1)

        val root = FrameLayout(this); root.setBackgroundColor(Color.BLACK)
        gl = GLSurfaceView(this)
        gl.setEGLContextClientVersion(3)
        gl.setRenderer(Renderer(S))
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        gl.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { S.touchX = e.x; S.touchY = e.y }
                else -> { S.touchX = -1f; S.touchY = -1f }
            }
            true
        }
        root.addView(gl, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val mono = Typeface.MONOSPACE
        val dp = resources.displayMetrics.density
        fun tv(sz: Float, c: Int) = TextView(this).apply { textSize = sz; setTextColor(c); typeface = mono }

        val top = LinearLayout(this); top.orientation = LinearLayout.HORIZONTAL
        top.setPadding((20 * dp).toInt(), (36 * dp).toInt(), (20 * dp).toInt(), 0)
        val title = tv(14f, Color.WHITE).apply { text = "magnet"; typeface = Typeface.create(mono, Typeface.BOLD) }
        statusTv = tv(12f, Color.GRAY); statusTv.gravity = Gravity.END
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(statusTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val bar = LinearLayout(this); bar.orientation = LinearLayout.VERTICAL
        bar.setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), (28 * dp).toInt())
        val row1 = LinearLayout(this)
        hintTv = tv(11f, Color.GRAY); readTv = tv(11f, Color.GRAY); readTv.gravity = Gravity.END
        row1.addView(hintTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(readTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        bar.addView(row1)
        val row2 = LinearLayout(this); row2.gravity = Gravity.CENTER_VERTICAL
        row2.addView(tv(11f, Color.GRAY).apply { text = "sens " })
        val sb = SeekBar(this); sb.max = 100; sb.progress = 50
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { S.sens = p }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        row2.addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(row2)
        val row3 = LinearLayout(this)
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; typeface = mono; textSize = 12f
            setTextColor(Color.WHITE); setBackgroundColor(Color.BLACK)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.BLACK); setStroke((1 * dp).toInt().coerceAtLeast(1), Color.DKGRAY); cornerRadius = 6 * dp }
            setOnClickListener { f() }
        }
        val lp = { LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f).apply { setMargins((3 * dp).toInt(), (6 * dp).toInt(), (3 * dp).toInt(), 0) } }
        row3.addView(btn("Zero") { say(if (zero()) "zeroed here" else "no fresh sensor data") }, lp())
        row3.addView(btn("Flip pole") {
            S.flip = -S.flip; prefs.edit().putInt("flip", S.flip).apply()
            say(if (S.flip > 0) "pole as taught" else "pole flipped")
        }, lp())
        row3.addView(btn("Fullscreen") { setFull(!fullscreen) }, lp())
        bar.addView(row3)
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        ui.post(object : Runnable { override fun run() { tick(); ui.postDelayed(this, 100) } })
    }

    private fun setFull(on: Boolean) {
        fullscreen = on
        window.decorView.systemUiVisibility = if (on) (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE) else View.SYSTEM_UI_FLAG_VISIBLE
    }
    private fun say(t: String) { hintTv.text = t; ui.removeCallbacks(clear); ui.postDelayed(clear, 2500) }
    private val clear = Runnable { hintTv.text = "" }

    private fun zero(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val a = S.buf.filter { now - it.first < 1000 }
        if (a.size < 8 || S.same >= 15) return false
        S.b0 = FloatArray(3) { i -> a.sumOf { it.second[i].toDouble() }.toFloat() / a.size }
        S.res = floatArrayOf(0f, 0f, 0f); return true
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val frozen = S.m == null || now - S.t > 1500 || S.same >= 15
        if (S.auto && S.b0 == null && !frozen && S.buf.size > 20 && now - S.buf.first().first > 1000) {
            if (zero()) { S.auto = false; say("auto-zeroed. keep magnets away, then use Zero if needed") }
        }
        val touching = S.touchX >= 0
        statusTv.text = when {
            touching -> "touch demo"
            S.m == null -> "waiting for sensor"
            frozen -> "sensor frozen, recovering"
            S.b0 != null -> "live"
            else -> "zeroing"
        }
        statusTv.setTextColor(if (!touching && !frozen && S.b0 != null) Color.WHITE else Color.GRAY)
        readTv.text = if (!frozen && S.b0 != null) S.readout else ""
    }

    override fun onResume() {
        super.onResume(); gl.onResume()
        val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val grv = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (mag == null) { say("no magnetometer on this device"); return }
        sm.registerListener(this, mag, 10000)   // 100 Hz requested; the sensor sets the real rate
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
        if (S.same < 15 && b0 != null) {   // baseline is kept in the fixed frame, so tilting does not create a residual
            val b = rot(rotQ, b0, true)
            S.res = floatArrayOf(m[0] - b[0], m[1] - b[1], m[2] - b[2])
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

class Renderer(private val S: State) : GLSurfaceView.Renderer {
    private var prog = 0
    private var w = 1; private var h = 1
    private var tPrev = SystemClock.elapsedRealtime()
    private var sAng = 0f; private var sLean = 0f; private var ssc = 1f; private val rs = floatArrayOf(0f, 0f, 0f)
    private var uRes = 0; private var uT = 0; private var uDir = 0; private var uLean = 0; private var uSc = 0
    private val tri = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)); position(0) }

    private fun sh(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s); return s
    }
    override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {
        val vs = "#version 300 es\nin vec2 p;void main(){gl_Position=vec4(p,0,1);}"
        prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, sh(GLES30.GL_VERTEX_SHADER, vs)); GLES30.glAttachShader(prog, sh(GLES30.GL_FRAGMENT_SHADER, FS))
        GLES30.glLinkProgram(prog); GLES30.glUseProgram(prog)
        uRes = GLES30.glGetUniformLocation(prog, "res"); uT = GLES30.glGetUniformLocation(prog, "t")
        uDir = GLES30.glGetUniformLocation(prog, "dir"); uLean = GLES30.glGetUniformLocation(prog, "lean"); uSc = GLES30.glGetUniformLocation(prog, "sc")
    }
    override fun onSurfaceChanged(g: GL10?, W: Int, H: Int) { w = W; h = H; GLES30.glViewport(0, 0, W, H) }

    override fun onDrawFrame(g: GL10?) {
        val now = SystemClock.elapsedRealtime()
        val dt = min(0.05f, (now - tPrev) / 1000f); tPrev = now
        val frozen = S.m == null || now - S.t > 1500 || S.same >= 15
        var dx = 0f; var dy = 0f; var lean = 0f; var sc = 1f
        var readout = ""
        if (S.touchX >= 0) {
            val ddx = S.touchX - w / 2f; val ddy = h / 2f - S.touchY; val m = hypot(ddx, ddy).coerceAtLeast(1f)
            dx = ddx / m; dy = ddy / m; lean = min(1f, m / (min(w, h) * .5f))
        } else if (!frozen && S.b0 != null) {
            val ka = 1f - exp(-dt / 0.15f); for (i in 0..2) rs[i] += (S.res[i] - rs[i]) * ka
            val r = floatArrayOf(rs[0] * S.flip, rs[1] * S.flip, rs[2] * S.flip)
            val mag = len3(r); val sens = 0.5f + S.sens / 50f
            readout = "residual %.0f uT".format(mag)
            if (mag > 3f / sens) {
                val c = Classifier.classify(r)
                val th = Math.toRadians(c.ang.toDouble())
                dx = sin(th).toFloat(); dy = cos(th).toFloat()   // 0 deg = top of screen, clockwise
                val confGate = ((c.conc - 0.4f) / 0.4f).coerceIn(0f, 1f)   // broad posterior (bottom zone) leans less
                val fitGate = ((6f - c.fit) / 3f).coerceIn(0f, 1f)          // field unlike anything taught
                val strength = (0.6f + 0.4f * ln(c.k) / ln(2f)).coerceIn(0.2f, 1f)
                lean = strength * confGate * fitGate
                sc = 1f + 0.2f * min(1f, ln(1f + mag) / 6f) * (1f - fitGate)
                readout += "  angle %.0f  conf %.2f".format(c.ang, c.conc) + (if (c.fit > 4f) "  unfamiliar" else "")
            }
        }
        S.readout = readout
        // smoothing: angle follows slowly with a slew cap and only in proportion to lean confidence;
        // lean rises over ~0.45 s and falls over ~0.9 s, both rate-capped, so noise bursts do not snap the blob
        val tl = lean
        if (hypot(dx, dy) > 0.1f && tl > 0.02f) {
            val ta = atan2(dx, dy)
            var da = ta - sAng; da = atan2(sin(da), cos(da))
            val wgt = min(1f, tl / 0.3f)
            val step = da * (1f - exp(-dt * wgt / 0.3f))
            val cap = 3.0f * dt
            sAng += step.coerceIn(-cap, cap)
        }
        val tau = if (tl > sLean) 0.45f else 0.9f
        val dl = ((tl - sLean) * (1f - exp(-dt / tau))).coerceIn(-1.2f * dt, 1.2f * dt)
        sLean += dl
        ssc += (sc - ssc) * (1f - exp(-dt / 0.6f))
        val ux = sin(sAng); val uy = cos(sAng); val m = sLean
        GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(prog)
        GLES30.glUniform2f(uRes, w.toFloat(), h.toFloat()); GLES30.glUniform1f(uT, now / 1000f % 3600f)
        GLES30.glUniform2f(uDir, ux, uy); GLES30.glUniform1f(uLean, min(1f, m)); GLES30.glUniform1f(uSc, ssc)
        val loc = GLES30.glGetAttribLocation(prog, "p")
        GLES30.glEnableVertexAttribArray(loc); GLES30.glVertexAttribPointer(loc, 2, GLES30.GL_FLOAT, false, 0, tri)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    companion object {
        val FS = """#version 300 es
precision highp float;
uniform vec2 res; uniform float t; uniform vec2 dir; uniform float lean; uniform float sc;
out vec4 o;
float smin(float a,float b,float k){float h=max(k-abs(a-b),0.)/k;return min(a,b)-h*h*k*.25;}
float sdf(vec2 p){
  float R=.27*sc;
  vec2 c0=-dir*lean*.04;
  float a=atan(p.y,p.x);
  float w=.004*sin(a*3.+t*1.1)+.003*sin(a*5.-t*1.7)+lean*.006*sin(a*7.+t*3.);
  float d1=length(p-c0)-R-w;
  float r2=R*(.18+.42*lean);
  vec2 c1=c0+dir*R*(.55+.95*lean);
  float d2=length(p-c1)-r2;
  float k=R*(.25+.5*lean);
  return mix(d1,smin(d1,d2,k),step(.001,length(dir))*smoothstep(0.,.05,lean));
}
float H(vec2 p){
  float d=-sdf(p); if(d<=0.) return 0.;
  float u=clamp(d/.17,0.,1.);
  float rim=.13*sqrt(1.-(1.-u)*(1.-u));
  float bump=.07*exp(-dot(p+dir*lean*.05,p+dir*lean*.05)/.045);
  return rim+bump*smoothstep(0.,.08,d);
}
float env(vec3 r){
  float up=r.y*.5+.5;
  float sky=pow(up,1.6);
  float hor=smoothstep(.0,.03,abs(r.y-.02));
  float soft=smoothstep(.55,.62,sin(r.x*3.4+r.y*1.3+.8))*smoothstep(.15,.5,r.y)*.9;
  float strip=smoothstep(.93,.96,sin(r.x*7.+2.))*.6*step(r.y,.0);
  float v=mix(.05,.95,sky);
  v=mix(.0,v,hor)+soft+strip;
  return clamp(v,0.,1.);
}
void main(){
  vec2 uv=(gl_FragCoord.xy-.5*res)/min(res.x,res.y);
  float d=sdf(uv);
  float e=1.5/min(res.x,res.y);
  float cov=1.-smoothstep(-e,e,d);
  vec2 q=vec2(.002,0.);
  float hx=H(uv+q.xy)-H(uv-q.xy), hy=H(uv+q.yx)-H(uv-q.yx);
  vec3 n=normalize(vec3(-hx/(2.*q.x)*.9,-hy/(2.*q.x)*.9,1.));
  vec3 r=reflect(vec3(0,0,-1),n);
  float c=env(r);
  float fres=pow(1.-n.z,3.);
  c=clamp(c*(.85+.15*n.z)+fres*.25,0.,1.);
  c=pow(c,.9);
  o=vec4(vec3(c*cov),1.);
}"""
    }
}
