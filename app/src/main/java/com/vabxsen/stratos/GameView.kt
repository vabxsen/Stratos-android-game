package com.vabxsen.stratos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/**
 * The whole game world: an isometric tower of slabs drawn on a Canvas, driven by Choreographer
 * so it runs at the display's native refresh rate. UI chrome (score, menus) lives in MainActivity.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    interface Listener {
        fun onPlace()
        fun onPerfect(combo: Int)
        fun onScore(score: Int)
        fun onGameOver(score: Int)
        fun onInkChanged(dark: Boolean)
    }

    enum class State { MENU, PLAYING, PAUSED, OVER }

    var listener: Listener? = null
    var reduceMotion = false

    /** Debug builds only: lands every slab perfectly, for testing the full climb. */
    var autoPlay = false
    var state = State.MENU
        private set
    var score = 0
        private set

    // ---------- world constants ----------
    private companion object {
        const val BASE = 200f
        const val SLAB = 34f
        const val RANGE = 290f
        const val PERFECT = 6f
        const val STAGE = 12f
        val C30 = cos(Math.PI / 6).toFloat()
        const val S30 = 0.5f
        val SKIES = arrayOf(
            intArrayOf(0xFFF2A7BD.toInt(), 0xFFFFE4C9.toInt()), // dawn
            intArrayOf(0xFF7DB4EE.toInt(), 0xFFD9EDFF.toInt()), // day
            intArrayOf(0xFFE9965A.toInt(), 0xFFFFE0A3.toInt()), // golden hour
            intArrayOf(0xFF4A3A8E.toInt(), 0xFFF08A7E.toInt()), // dusk
            intArrayOf(0xFF101646.toInt(), 0xFF3B3579.toInt()), // night
            intArrayOf(0xFF05061A.toInt(), 0xFF1D1548.toInt())  // deep sky
        )
    }

    private class Box(
        var cx: Float, var cz: Float, var w: Float, var d: Float, var y: Float,
        val hue: Float, val base: Boolean = false
    ) {
        var axisX = true
        var center = 0f
        var dir = 1f
        var speed = 0f
        var vx = 0f
        var vy = 0f
        var a = 1f
        val top = hsl(hue, 0.62f, 0.76f)
        val right = hsl(hue, 0.50f, 0.60f)
        val front = hsl(hue, 0.46f, 0.47f)
        val rim = hsl(hue, 0.90f, 0.92f)

        var pos: Float
            get() = if (axisX) cx else cz
            set(v) { if (axisX) cx = v else cz = v }
        var size: Float
            get() = if (axisX) w else d
            set(v) { if (axisX) w = v else d = v }

        fun copy() = Box(cx, cz, w, d, y, hue).also {
            it.axisX = axisX; it.center = center; it.dir = dir; it.speed = speed
        }

        private fun hsl(h: Float, s: Float, l: Float) = ColorUtils.HSLToColor(floatArrayOf(h, s, l))
    }

    private class Ripple(val cx: Float, val cz: Float, val w: Float, val d: Float, val y: Float) {
        var t = 0f
    }

    private class Star(val x: Float, val y: Float, val r: Float, val p: Float)
    private class Cloud(val x: Float, val y: Float, val s: Float, val v: Float)

    private val stars = List(140) {
        Star(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 1.3f + 0.3f, Random.nextFloat() * 6.2832f)
    }
    private val clouds = List(7) { i ->
        Cloud(Random.nextFloat(), i / 7f, 0.6f + Random.nextFloat() * 0.8f, 0.004f + Random.nextFloat() * 0.008f)
    }

    // ---------- game state ----------
    private val stack = ArrayList<Box>()
    private var current: Box? = null
    private val debris = ArrayList<Box>()
    private val ripples = ArrayList<Ripple>()
    private var combo = 0
    private var camY = SLAB
    private var camYTarget = SLAB
    private var zoom = 1f
    private var zoomTarget = 1f
    private var skyLvl = 0f
    private var hue0 = 0f
    private var time = 0f
    private var inkDark = true
    private var skyTop = SKIES[0][0]
    private var skyBottom = SKIES[0][1]

    private var W = 0f
    private var H = 0f
    private var SCALE = 1f
    private val dp = resources.displayMetrics.density

    // ---------- drawing resources ----------
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val shaded = Paint(Paint.DITHER_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val puffPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val puffRect = RectF()
    private val puff: Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { bmp ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(64f, 64f, 64f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        Canvas(bmp).drawCircle(64f, 64f, 64f, p)
    }
    private val cloudPuffs = arrayOf(floatArrayOf(0f, 0f, 1f), floatArrayOf(0.9f, 0.15f, 0.75f), floatArrayOf(-0.85f, 0.2f, 0.7f))

    // ---------- frame loop ----------
    private var hostResumed = false
    private var looping = false
    private var lastFrameNanos = 0L

    init {
        isFocusable = true
        reset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        W = w.toFloat(); H = h.toFloat()
        SCALE = min(W, H * 0.85f) / 640f
        if (state == State.OVER) frameTower()
    }

    /** Called from the Activity's onResume/onPause so nothing animates in the background. */
    fun onHostResume() { hostResumed = true; updateLoop() }
    fun onHostPause() { hostResumed = false; updateLoop() }

    private fun updateLoop() {
        val want = hostResumed && state != State.PAUSED
        if (want && !looping) {
            looping = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        } else if (!want && looping) {
            looping = false
            Choreographer.getInstance().removeFrameCallback(this)
        }
        invalidate()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!looping) return
        val dt = if (lastFrameNanos == 0L) 0f else min((frameTimeNanos - lastFrameNanos) / 1e9f, 0.05f)
        lastFrameNanos = frameTimeNanos
        update(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        looping = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    // ---------- public controls ----------
    fun start() {
        reset()
        state = State.PLAYING
        updateLoop()
    }

    fun pause() {
        if (state != State.PLAYING) return
        state = State.PAUSED
        updateLoop()
    }

    fun resume() {
        if (state != State.PAUSED) return
        state = State.PLAYING
        updateLoop()
    }

    /** Ends a paused or running climb: the moving slab simply falls away. */
    fun endRun() {
        if (state != State.PLAYING && state != State.PAUSED) return
        current?.let { debris.add(it.copy().apply { vx = 0f; vy = 0f }) }
        current = null
        gameOver()
        updateLoop()
    }

    fun drop() {
        if (state != State.PLAYING || current == null) return
        place()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> drop()
            MotionEvent.ACTION_UP -> {
                suppressClick = true
                performClick()
                suppressClick = false
            }
        }
        return true
    }

    private var suppressClick = false

    // Touch drops on ACTION_DOWN for responsiveness; this path serves TalkBack and switch access.
    override fun performClick(): Boolean {
        val handled = super.performClick()
        if (!suppressClick) drop()
        return handled
    }

    // ---------- rules ----------
    private fun hueFor(n: Int) = (hue0 + n * 7f) % 360f

    private fun reset() {
        hue0 = 190f + Random.nextFloat() * 140f
        stack.clear()
        stack.add(Box(0f, 0f, BASE, BASE, 0f, hueFor(0), base = true))
        debris.clear(); ripples.clear()
        score = 0; combo = 0
        camYTarget = SLAB; zoomTarget = 1f
        spawn()
    }

    private fun spawn() {
        val prev = stack.last()
        val n = stack.size
        val side = if ((n / 2) % 2 == 1) 1f else -1f
        current = Box(prev.cx, prev.cz, prev.w, prev.d, n * SLAB, hueFor(n)).apply {
            axisX = n % 2 == 1
            center = if (axisX) prev.cx else prev.cz
            dir = -side
            speed = min(250f + n * 5f, 560f)
            pos = center + side * RANGE
        }
    }

    private fun place() {
        val prev = stack.last()
        val c = current ?: return
        val prevPos = if (c.axisX) prev.cx else prev.cz
        val delta = c.pos - prevPos
        val overlap = c.size - abs(delta)

        if (overlap <= 0f) {
            debris.add(c.copy().apply { vy = 0f; vx = sign(delta) * 60f })
            current = null
            gameOver()
            return
        }

        if (abs(delta) <= PERFECT) {
            c.pos = prevPos
            combo++
            if (combo >= 3) c.size = min(c.size + 12f, BASE)
            ripples.add(Ripple(c.cx, c.cz, c.w, c.d, c.y + SLAB))
            listener?.onPerfect(combo)
        } else {
            combo = 0
            val s = sign(delta)
            val mid = prevPos + delta / 2f
            val chip = c.copy().apply {
                size = abs(delta)
                pos = mid + s * (overlap / 2f + abs(delta) / 2f)
                vy = 0f; vx = s * 40f
            }
            c.size = overlap
            c.pos = mid
            debris.add(chip)
            listener?.onPlace()
        }

        stack.add(Box(c.cx, c.cz, c.w, c.d, c.y, c.hue))
        score++
        camYTarget = stack.size * SLAB
        listener?.onScore(score)
        spawn()
    }

    private fun gameOver() {
        state = State.OVER
        frameTower()
        listener?.onGameOver(score)
    }

    private fun frameTower() {
        val towerH = stack.size * SLAB
        camYTarget = towerH / 2f
        zoomTarget = min(1f, (H * 0.55f) / ((towerH + 160f) * SCALE))
    }

    // ---------- simulation ----------
    private fun update(dt: Float) {
        time += dt
        current?.let { c ->
            val before = c.pos - c.center
            c.pos += c.dir * c.speed * dt
            if (c.pos > c.center + RANGE) { c.pos = c.center + RANGE; c.dir = -1f }
            if (c.pos < c.center - RANGE) { c.pos = c.center - RANGE; c.dir = 1f }
            if (autoPlay && state == State.PLAYING && sign(before) != sign(c.pos - c.center)) {
                c.pos = c.center
                place()
            }
        }
        val k = 1f - exp(-dt * if (state == State.OVER) 2.2f else 5f)
        camY += (camYTarget - camY) * k
        zoom += (zoomTarget - zoom) * k
        if (state != State.OVER) skyLvl += (score - skyLvl) * (1f - exp(-dt * 1.4f))

        for (d in debris) {
            d.vy -= 1700f * dt
            d.y += d.vy * dt
            d.pos += d.vx * dt
            d.a = max(0f, d.a - dt * 0.7f)
        }
        debris.removeAll { it.a <= 0f || it.y <= camY - 1500f }
        val rippleLife = if (reduceMotion) 0.3f else 0.7f
        for (r in ripples) r.t += dt / rippleLife
        ripples.removeAll { it.t >= 1f }

        updateSky()
    }

    private fun updateSky() {
        val f = max(0f, skyLvl / STAGE)
        val i = min(floor(f).toInt(), SKIES.size - 1)
        val j = min(i + 1, SKIES.size - 1)
        var t = (f - i).coerceIn(0f, 1f)
        t = t * t * (3f - 2f * t)
        skyTop = ColorUtils.blendARGB(SKIES[i][0], SKIES[j][0], t)
        skyBottom = ColorUtils.blendARGB(SKIES[i][1], SKIES[j][1], t)
        val wantDark = ColorUtils.calculateLuminance(ColorUtils.blendARGB(skyTop, skyBottom, 0.5f)) > 0.5
        if (wantDark != inkDark) {
            inkDark = wantDark
            listener?.onInkChanged(wantDark)
        }
    }

    // ---------- projection & drawing ----------
    private fun sx(x: Float, z: Float) = W / 2f + (x - z) * C30 * SCALE * zoom
    private fun sy(x: Float, y: Float, z: Float) = H * 0.6f + ((x + z) * S30 - (y - camY)) * SCALE * zoom

    private fun quad(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float
    ) {
        path.rewind()
        path.moveTo(ax, ay); path.lineTo(bx, by); path.lineTo(cx, cy); path.lineTo(dx, dy)
        path.close()
    }

    private fun drawBox(canvas: Canvas, b: Box, yb: Float, yt: Float, a: Float = 1f) {
        val x0 = b.cx - b.w / 2f; val x1 = b.cx + b.w / 2f
        val z0 = b.cz - b.d / 2f; val z1 = b.cz + b.d / 2f
        val alpha = (a * 255f).toInt().coerceIn(0, 255)

        quad(sx(x1, z0), sy(x1, yt, z0), sx(x1, z1), sy(x1, yt, z1), sx(x1, z1), sy(x1, yb, z1), sx(x1, z0), sy(x1, yb, z0))
        fill.color = b.right; fill.alpha = alpha
        canvas.drawPath(path, fill)

        quad(sx(x0, z1), sy(x0, yt, z1), sx(x1, z1), sy(x1, yt, z1), sx(x1, z1), sy(x1, yb, z1), sx(x0, z1), sy(x0, yb, z1))
        fill.color = b.front; fill.alpha = alpha
        canvas.drawPath(path, fill)

        quad(sx(x0, z0), sy(x0, yt, z0), sx(x1, z0), sy(x1, yt, z0), sx(x1, z1), sy(x1, yt, z1), sx(x0, z1), sy(x0, yt, z1))
        fill.color = b.top; fill.alpha = alpha
        canvas.drawPath(path, fill)

        // soft rim light on the near edges
        path.rewind()
        path.moveTo(sx(x0, z1), sy(x0, yt, z1))
        path.lineTo(sx(x1, z1), sy(x1, yt, z1))
        path.lineTo(sx(x1, z0), sy(x1, yt, z0))
        line.color = b.rim; line.alpha = (0.55f * alpha).toInt()
        line.strokeWidth = max(dp, SCALE * zoom * 1.2f)
        canvas.drawPath(path, line)
    }

    private fun drawSky(canvas: Canvas) {
        shaded.shader = LinearGradient(0f, 0f, 0f, H, skyTop, skyBottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, W, H, shaded)

        // a low sun / moon glow that drifts down as you climb
        val glowY = H * 0.75f + skyLvl * H * 0.012f
        val night = ((skyLvl - 40f) / 16f).coerceIn(0f, 1f)
        val glow = Color.argb(
            ((0.45f - night * 0.25f) * 255f).toInt(), 255,
            (236f - night * 40f).toInt(), (210f + night * 40f).toInt().coerceAtMost(255)
        )
        shaded.shader = RadialGradient(W * 0.72f, glowY, max(W, H) * 0.55f, glow, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, W, H, shaded)
        shaded.shader = null

        // stars
        val sa = ((skyLvl - 34f) / 14f).coerceIn(0f, 1f)
        if (sa > 0f) {
            val starScale = dp * max(1f, SCALE / dp)
            for (s in stars) {
                val tw = 0.55f + 0.45f * sin(time * 1.6f + s.p)
                val y = mod(s.y * H + camY * 0.06f * SCALE, H)
                starPaint.color = Color.argb((sa * tw * 255f).toInt(), 255, 250, 240)
                canvas.drawCircle(s.x * W, y, s.r * starScale, starPaint)
            }
        }

        // clouds you pass through on the way up
        val ca = max(0f, 1f - max(0f, skyLvl - 20f) / 12f)
        if (ca > 0f) {
            val span = H * 1.6f
            puffPaint.alpha = (0.5f * ca * 255f).toInt()
            for (c in clouds) {
                val x = (mod(c.x + time * c.v, 1.4f) - 0.2f) * W
                val y = mod(c.y * span + camY * 0.45f * SCALE, span) - H * 0.3f
                val r = 70f * c.s * dp * max(0.8f, SCALE / dp)
                for (p in cloudPuffs) {
                    val px = x + r * p[0]; val py = y + r * p[1]; val rr = r * p[2] * 1.2f
                    puffRect.set(px - rr, py - rr, px + rr, py + rr)
                    canvas.drawBitmap(puff, null, puffRect, puffPaint)
                }
            }
        }
    }

    private fun drawFog(canvas: Canvas) {
        val top = H * 0.68f
        shaded.shader = LinearGradient(
            0f, top, 0f, H,
            ColorUtils.setAlphaComponent(skyBottom, 0), ColorUtils.setAlphaComponent(skyBottom, 242),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, top, W, H, shaded)
        shaded.shader = null
    }

    override fun onDraw(canvas: Canvas) {
        if (W == 0f) return
        drawSky(canvas)

        val s = SCALE * zoom
        for (b in stack) {
            val yt = b.y + SLAB
            val yb = if (b.base) -2000f else b.y
            val syTop = H * 0.6f - (yt - camY) * s
            if (!b.base && syTop - BASE * s > H + 40f * dp) continue
            drawBox(canvas, b, yb, yt)
        }
        for (r in ripples) {
            val grow = r.t * 40f
            val x0 = r.cx - r.w / 2f - grow; val x1 = r.cx + r.w / 2f + grow
            val z0 = r.cz - r.d / 2f - grow; val z1 = r.cz + r.d / 2f + grow
            quad(sx(x0, z0), sy(x0, r.y, z0), sx(x1, z0), sy(x1, r.y, z0), sx(x1, z1), sy(x1, r.y, z1), sx(x0, z1), sy(x0, r.y, z1))
            line.color = Color.WHITE; line.alpha = ((1f - r.t) * 0.9f * 255f).toInt()
            line.strokeWidth = max(1.5f * dp, 2.5f * s)
            canvas.drawPath(path, line)
        }
        current?.let { drawBox(canvas, it, it.y, it.y + SLAB) }
        for (d in debris) drawBox(canvas, d, d.y, d.y + SLAB, d.a)
        drawFog(canvas)
    }

    private fun mod(v: Float, m: Float): Float = ((v % m) + m) % m
}
