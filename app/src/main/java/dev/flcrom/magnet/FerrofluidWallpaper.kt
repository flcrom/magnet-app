package dev.flcrom.magnet

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import java.io.File
import kotlin.math.sqrt

/**
 * Ferrofluid live wallpaper: the same FerroRenderer/shaders as FerrofluidActivity,
 * driven directly by the magnetometer (no Termux relay). Calm until a magnet is near;
 * low-confidence estimates freeze instead of jumping; fps throttles when idle/invisible.
 * Auto-zeros the baseline ~1.2s after start, gated to a plausible earth-field norm
 * (25-80uT) so a magnet resting on the glass is not absorbed into the baseline;
 * slow re-zero creeps when clearly no magnet. Sensor reader bounces itself if the
 * event stream wedges. Model reloads when the training files change (checked 60s).
 */
class FerrofluidWallpaper : WallpaperService() {

    companion object {
        // set by the QS tile / double-tap; consumed by the engine's sensor loop (same process)
        @Volatile var zeroRequestedAt = 0L
    }

    inner class FerroEngine : Engine(), SensorEventListener {
        private var lastZeroHandled = 0L
        private var lastWallpaperTap = 0L
        private val S = State()
        private lateinit var sm: SensorManager
        private var rotQ = floatArrayOf(0f, 0f, 0f, 1f)
        @Volatile private var visible = false
        @Volatile private var lastMagAt = 0L
        private var renderThread: RenderThread? = null
        private var magSensor: Sensor? = null
        private var grvSensor: Sensor? = null
        private var sensorsOn = false

        override fun onCreate(holder: SurfaceHolder?) {
            super.onCreate(holder)
            sm = getSystemService(SENSOR_SERVICE) as SensorManager
            magSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            grvSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        }

        private fun registerSensors() {
            if (sensorsOn) return
            val okM = magSensor?.let { sm.registerListener(this, it, 100000) }  // 10Hz: the 30Hz registration starved the co-hosted Termux mag client on this device
            val okG = grvSensor?.let { sm.registerListener(this, it, 100000) }
            sensorsOn = true
            Log.i("FerroWall", "sensors registered: mag=$okM grv=$okG")
        }
        private fun unregisterSensors() { if (sensorsOn) { sm.unregisterListener(this); sensorsOn = false; Log.i("FerroWall", "sensors unregistered") } }

        override fun onVisibilityChanged(v: Boolean) {
            Log.i("FerroWall", "visibility -> $v")
            visible = v
            if (v) { lastMagAt = SystemClock.elapsedRealtime(); registerSensors() }
            else unregisterSensors()
            renderThread?.visibilityChanged(v)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            val rt = RenderThread(holder)
            renderThread = rt
            rt.start()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            renderThread?.shutdown(); renderThread = null
            unregisterSensors()
        }

        override fun onDestroy() { super.onDestroy(); unregisterSensors() }

        override fun onSensorChanged(e: SensorEvent) {
            if (e.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                val v = e.values
                val w = if (v.size > 3) v[3] else sqrt(kotlin.math.max(0f, 1f - v[0]*v[0] - v[1]*v[1] - v[2]*v[2]))
                rotQ = floatArrayOf(v[0], v[1], v[2], w); S.q = rotQ; return
            }
            if (e.sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
            val now = SystemClock.elapsedRealtime()
            lastMagAt = now
            val m = floatArrayOf(e.values[0], e.values[1], e.values[2])
            val old = S.m
            if (old != null && old[0] == m[0] && old[1] == m[1] && old[2] == m[2]) S.same++ else S.same = 0
            S.m = m; S.t = now; S.samples++
            if (S.samples % 900L == 0L) Log.i("FerroWall", "sensor alive, samples=${S.samples}")
            S.buf.addLast(Pair(now, rot(rotQ, m, false)))
            while (S.buf.isNotEmpty() && now - S.buf.first().first > 1500) S.buf.removeFirst()
            val zf = File(filesDir, "zero_request")
            if (zf.exists()) { try { zf.delete() } catch (_: Throwable) {}; zeroRequestedAt = now }
            if (zeroRequestedAt > lastZeroHandled && S.buf.size >= 8) {
                lastZeroHandled = zeroRequestedAt
                val a = S.buf.toList()
                S.b0 = FloatArray(3) { i -> a.sumOf { it.second[i].toDouble() }.toFloat() / a.size }
                S.res = floatArrayOf(0f, 0f, 0f)
                Log.i("FerroWall", "manual zero applied, baseline norm %.1f uT".format(len3(S.b0!!)))
            }
            val b0 = S.b0
            if (S.same < 15 && b0 != null) {
                val b = rot(rotQ, b0, true)
                S.res = floatArrayOf(m[0] - b[0], m[1] - b[1], m[2] - b[2])
                if (len3(S.res) < 1.2f) {   // slow re-zero, ~60s time constant
                    val fix = rot(rotQ, S.res, false)
                    val bf = rot(rotQ, b0, false)
                    S.b0 = rot(rotQ, floatArrayOf(bf[0] + fix[0]*0.0006f, bf[1] + fix[1]*0.0006f, bf[2] + fix[2]*0.0006f), true)
                }
            }
            // one-shot auto-zero ~1.2s in, gated to plausible earth field so a resting magnet is not absorbed
            if (S.b0 == null && S.same < 15 && S.buf.size > 20 && now - S.buf.first().first > 1000) {
                val a = S.buf.toList()
                val avg = FloatArray(3) { i -> a.sumOf { it.second[i].toDouble() }.toFloat() / a.size }
                val n = len3(avg)
                if (n in 25f..80f) { S.b0 = avg; S.res = floatArrayOf(0f, 0f, 0f); Log.i("FerroWall", "auto-zero norm %.1f uT".format(n)) }
            }
            // reader-bounce watchdog: if the stream wedged (no events) the visibility/render loop re-registers us;
            // here we only need the timestamp updated, see RenderThread.watchdog()
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}

        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: android.os.Bundle?, resultRequested: Boolean): android.os.Bundle? {
            if (action == "android.wallpaper.tap") {
                val now = SystemClock.elapsedRealtime()
                if (now - lastWallpaperTap < 400) { zeroRequestedAt = now; Log.i("FerroWall", "double-tap zero requested") }
                lastWallpaperTap = now
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        inner class RenderThread(private val holder: SurfaceHolder) : Thread("FerroWallGL") {
            @Volatile private var running = true
            @Volatile private var wantVisible = false
            private var renderer: FerroRenderer? = null
            private var lastModelCheck = 0L
            private var slideMtime = 0L; private var gridMtime = 0L

            fun visibilityChanged(v: Boolean) { wantVisible = v }
            fun shutdown() { running = false; interrupt() }

            override fun run() {
                var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
                var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
                var srf: EGLSurface = EGL14.EGL_NO_SURFACE
                try {
                    dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    val ver = IntArray(2)
                    if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) throw RuntimeException("eglInitialize")
                    val cfg = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
                    EGL14.eglChooseConfig(dpy, intArrayOf(
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, 0x40 /*EGL_OPENGL_ES3_BIT_KHR*/,
                        EGL14.EGL_NONE), 0, cfg, 0, 1, n, 0)
                    ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT,
                        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                    srf = EGL14.eglCreateWindowSurface(dpy, cfg[0], holder, intArrayOf(EGL14.EGL_NONE), 0)
                    EGL14.eglMakeCurrent(dpy, srf, srf, ctx)
                    val r = FerroRenderer(S, 1080f, 2400f)
                    r.grid = GridModel.load(filesDir)
                    r.onSurfaceCreated(null, null)
                    val sz = IntArray(2)
                    EGL14.eglQuerySurface(dpy, srf, EGL14.EGL_WIDTH, sz, 0)
                    EGL14.eglQuerySurface(dpy, srf, EGL14.EGL_HEIGHT, sz, 1)
                    r.onSurfaceChanged(null, sz[0], sz[1])
                    renderer = r
                    while (running) {
                        if (!wantVisible) { sleep(500); continue }
                        val now = SystemClock.elapsedRealtime()
                        // sensor wedge watchdog: bounce the listener if the stream died
                        if (sensorsOn && now - lastMagAt > 3000) {
                            Log.w("FerroWall", "mag stream stale ${now - lastMagAt}ms - bouncing listener")
                            unregisterSensors(); registerSensors(); lastMagAt = now
                        }
                        // hot-reload the model when training files change (off the GL thread is overkill at 60s cadence)
                        if (now - lastModelCheck > 60000) {
                            lastModelCheck = now
                            val sf = File(filesDir, "magnet_slide.jsonl"); val gf = File(filesDir, "magnet_grid.json")
                            if (sf.lastModified() != slideMtime || gf.lastModified() != gridMtime) {
                                slideMtime = sf.lastModified(); gridMtime = gf.lastModified()
                                Thread {
                                    val g = GridModel.load(filesDir)
                                    if (g != null) renderer?.grid = g
                                }.start()
                            }
                        }
                        r.onDrawFrame(null)
                        EGL14.eglSwapBuffers(dpy, srf)
                        // battery: fast while a magnet is engaging, 5fps idle
                        val active = len3(S.res) > 3f || now - S.estT < 4000
                        sleep(if (active) 33L else 200L)
                    }
                } catch (t: Throwable) {
                    Log.w("FerroWall", "render thread died", t)
                } finally {
                    if (dpy != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (srf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, srf)
                        if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx)
                        EGL14.eglTerminate(dpy)
                    }
                }
            }
        }
    }

    override fun onCreateEngine(): Engine = FerroEngine()
}
