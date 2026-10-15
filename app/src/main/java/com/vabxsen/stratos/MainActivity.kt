package com.vabxsen.stratos

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), GameView.Listener {

    private lateinit var game: GameView
    private lateinit var hud: View
    private lateinit var scoreText: TextView
    private lateinit var comboText: TextView
    private lateinit var hint: TextView
    private lateinit var overlay: GateLayout
    private lateinit var title: TextView
    private lateinit var lede: TextView
    private lateinit var best: TextView
    private lateinit var primary: TextView
    private lateinit var secondary: TextView
    private lateinit var pauseButton: ImageButton
    private lateinit var soundButton: ImageButton
    private lateinit var hapticsButton: ImageButton

    private lateinit var prefs: Prefs
    private lateinit var haptics: Haptics
    private val sfx = Sfx()

    private val handler = Handler(Looper.getMainLooper())
    private val showResults = Runnable { showGameOver() }
    private val hideCombo = Runnable { comboText.alpha = 0f }
    private var canActAt = 0L
    private var lastScore = 0
    private var newBest = false
    private var reduceMotion = false
    private var comboAnim: ValueAnimator? = null

    private var ink = INK_DARK
    private var inkSoft = INK_SOFT_DARK

    private val dp get() = resources.displayMetrics.density

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when (game.state) {
                GameView.State.PLAYING -> pauseGame()
                GameView.State.PAUSED -> resumeGame()
                else -> Unit
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContentView(R.layout.activity_main)

        game = findViewById(R.id.game)
        hud = findViewById(R.id.hud)
        scoreText = findViewById(R.id.score)
        comboText = findViewById(R.id.combo)
        hint = findViewById(R.id.hint)
        overlay = findViewById(R.id.overlay)
        title = findViewById(R.id.title)
        lede = findViewById(R.id.lede)
        best = findViewById(R.id.best)
        primary = findViewById(R.id.primary)
        secondary = findViewById(R.id.secondary)
        pauseButton = findViewById(R.id.pause)
        soundButton = findViewById(R.id.sound)
        hapticsButton = findViewById(R.id.haptics)

        prefs = Prefs(this)
        haptics = Haptics(this).apply { enabled = prefs.haptics }
        sfx.muted = prefs.muted
        reduceMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        game.reduceMotion = reduceMotion
        game.listener = this

        applyFonts()
        applyInsets()
        wireControls()
        applyInk(dark = true)
        onBackPressedDispatcher.addCallback(this, backCallback)

        showMenu()
        overlay.alpha = 1f

        if (BuildConfig.DEBUG && intent.getBooleanExtra("autoplay", false)) {
            game.autoPlay = true
            startGame()
        }
    }

    // ---------- lifecycle ----------
    override fun onResume() {
        super.onResume()
        hideSystemBars()
        sfx.start()
        game.onHostResume()
    }

    override fun onPause() {
        if (game.state == GameView.State.PLAYING) pauseGame()
        game.onHostPause()
        sfx.stop()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        comboAnim?.cancel()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
        else if (game.state == GameView.State.PLAYING) pauseGame()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------- flow ----------
    private fun startGame() {
        handler.removeCallbacks(showResults)
        game.start()
        prefs.climbs = prefs.climbs + 1
        scoreText.text = "0"
        comboText.alpha = 0f
        hideOverlay()
        fade(hud, true, 400)
        fade(hint, true, 600)
        pauseButton.visibility = View.VISIBLE
        playingChanged()
    }

    private fun pauseGame() {
        if (game.state != GameView.State.PLAYING) return
        game.pause()
        fade(hud, false, 250)
        fade(hint, false, 250)
        pauseButton.visibility = View.GONE
        showPaused()
        playingChanged()
    }

    private fun resumeGame() {
        if (game.state != GameView.State.PAUSED) return
        game.resume()
        hideOverlay()
        fade(hud, true, 300)
        if (game.score == 0) fade(hint, true, 400)
        pauseButton.visibility = View.VISIBLE
        playingChanged()
    }

    private fun endRun() {
        hideOverlay()
        game.endRun()
    }

    private fun primaryAction() {
        if (SystemClock.uptimeMillis() < canActAt) return
        when (game.state) {
            GameView.State.MENU, GameView.State.OVER -> startGame()
            GameView.State.PAUSED -> resumeGame()
            GameView.State.PLAYING -> Unit
        }
    }

    private fun secondaryAction() {
        if (SystemClock.uptimeMillis() < canActAt) return
        when (game.state) {
            GameView.State.PAUSED -> endRun()
            GameView.State.OVER -> shareScore()
            else -> Unit
        }
    }

    private fun playingChanged() {
        val playing = game.state == GameView.State.PLAYING
        backCallback.isEnabled = playing || game.state == GameView.State.PAUSED
        if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------- game callbacks ----------
    override fun onPlace() {
        sfx.place()
        haptics.tap()
    }

    override fun onPerfect(combo: Int) {
        sfx.perfect(combo)
        haptics.perfect()
        comboText.text = if (combo > 1) getString(R.string.perfect_combo, combo) else getString(R.string.perfect)
        popCombo()
    }

    override fun onScore(score: Int) {
        scoreText.text = score.toString()
        if (score == 1) fade(hint, false, 600)
    }

    override fun onGameOver(score: Int) {
        sfx.over()
        haptics.over()
        lastScore = score
        newBest = score > prefs.best
        if (newBest) prefs.best = score
        fade(hud, false, 400)
        fade(hint, false, 400)
        pauseButton.visibility = View.GONE
        playingChanged()
        handler.postDelayed(showResults, 1000)
    }

    override fun onInkChanged(dark: Boolean) = applyInk(dark)

    // ---------- overlay screens ----------
    private fun showMenu() {
        title.text = getString(R.string.app_name)
        lede.text = getString(R.string.lede)
        best.text = if (prefs.best > 0) getString(R.string.best, prefs.best) else ""
        primary.text = getString(R.string.start)
        secondary.visibility = View.GONE
        showOverlay()
    }

    private fun showPaused() {
        title.text = getString(R.string.paused)
        lede.text = getString(R.string.paused_lede)
        best.text = resources.getQuantityString(R.plurals.height_so_far, game.score, game.score)
        primary.text = getString(R.string.resume)
        secondary.text = getString(R.string.end_run)
        secondary.visibility = View.VISIBLE
        showOverlay(delayMs = 250)
    }

    private fun showGameOver() {
        title.text = resources.getQuantityString(R.plurals.slabs, lastScore, lastScore)
        lede.text = getString(
            when {
                newBest && lastScore > 0 -> R.string.over_best
                lastScore < 10 -> R.string.over_low
                else -> R.string.over_good
            }
        )
        best.text = getString(R.string.best, prefs.best)
        primary.text = getString(R.string.again)
        secondary.text = getString(R.string.share)
        secondary.visibility = if (lastScore > 0) View.VISIBLE else View.GONE
        showOverlay(delayMs = 350)
    }

    private fun showOverlay(delayMs: Long = 0) {
        canActAt = SystemClock.uptimeMillis() + delayMs
        overlay.passThrough = false
        overlay.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        overlay.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        overlay.visibility = View.VISIBLE
        overlay.animate().cancel()
        overlay.animate().alpha(1f).setDuration(500).start()
        title.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private fun hideOverlay() {
        overlay.passThrough = true
        overlay.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlay.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        overlay.animate().cancel()
        overlay.animate().alpha(0f).setDuration(500).withEndAction {
            if (overlay.passThrough) overlay.visibility = View.INVISIBLE
        }.start()
    }

    private fun shareScore() {
        val text = resources.getQuantityString(R.plurals.share_text, lastScore, lastScore)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }

    // ---------- controls ----------
    private fun wireControls() {
        overlay.setOnClickListener { primaryAction() }
        primary.setOnClickListener { primaryAction() }
        secondary.setOnClickListener { secondaryAction() }
        pauseButton.setOnClickListener { pauseGame() }

        soundButton.setOnClickListener {
            prefs.muted = !prefs.muted
            sfx.muted = prefs.muted
            updateToggles()
        }
        if (haptics.available) {
            hapticsButton.setOnClickListener {
                prefs.haptics = !prefs.haptics
                haptics.enabled = prefs.haptics
                haptics.perfect()
                updateToggles()
            }
        } else {
            hapticsButton.visibility = View.GONE
        }
        updateToggles()
    }

    private fun updateToggles() {
        soundButton.setImageResource(if (prefs.muted) R.drawable.ic_sound_off else R.drawable.ic_sound_on)
        soundButton.contentDescription = getString(if (prefs.muted) R.string.unmute else R.string.mute)
        hapticsButton.setImageResource(if (prefs.haptics) R.drawable.ic_vibrate else R.drawable.ic_vibrate_off)
        hapticsButton.contentDescription = getString(if (prefs.haptics) R.string.haptics_off else R.string.haptics_on)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val confirm = keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A
        if (confirm) {
            if (event.repeatCount == 0) {
                if (game.state == GameView.State.PLAYING) game.drop()
                else if (overlay.visibility == View.VISIBLE && !overlay.passThrough) primaryAction()
            }
            return true
        }
        if ((keyCode == KeyEvent.KEYCODE_P || keyCode == KeyEvent.KEYCODE_BUTTON_START) && event.repeatCount == 0) {
            if (game.state == GameView.State.PLAYING) pauseGame() else if (game.state == GameView.State.PAUSED) resumeGame()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- look ----------
    private fun applyFonts() {
        val unbounded800 = font("Unbounded.ttf", 800)
        val unbounded600 = font("Unbounded.ttf", 600)
        val figtree400 = font("Figtree.ttf", 400)
        val figtree600 = font("Figtree.ttf", 600)

        scoreText.typeface = unbounded800
        scoreText.fontFeatureSettings = "tnum"
        scoreText.letterSpacing = -0.03f
        title.typeface = unbounded800
        title.letterSpacing = -0.04f
        comboText.typeface = unbounded600
        primary.typeface = unbounded600
        lede.typeface = figtree400
        best.typeface = figtree400
        best.fontFeatureSettings = "tnum"
        hint.typeface = figtree400
        secondary.typeface = figtree600
    }

    private fun font(file: String, weight: Int): Typeface =
        Typeface.Builder(assets, "fonts/$file")
            .setFontVariationSettings("'wght' $weight")
            .setWeight(weight)
            .build() ?: Typeface.defaultFromStyle(if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)

    private fun applyInsets() {
        val ui = findViewById<View>(R.id.ui)
        ViewCompat.setOnApplyWindowInsetsListener(ui) { v, insets ->
            val i = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
        // Responsive type: the web version used clamp(min, vw, max).
        ui.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if (r - l == or - ol && b - t == ob - ot) return@addOnLayoutChangeListener
            val w = (r - l).toFloat()
            px(scoreText, (w * 0.13f).coerceIn(56 * dp, 96 * dp))
            px(title, (w * 0.12f).coerceIn(48 * dp, 92 * dp))
            overlay.setPadding(overlay.paddingLeft, ((b - t) * 0.12f).roundToInt(), overlay.paddingRight, overlay.paddingBottom)
        }
    }

    private fun px(view: TextView, size: Float) = view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)

    private fun applyInk(dark: Boolean) {
        ink = if (dark) INK_DARK else INK_LIGHT
        inkSoft = if (dark) INK_SOFT_DARK else INK_SOFT_LIGHT
        for (t in listOf(scoreText, comboText, title, primary)) t.setTextColor(ink)
        for (t in listOf(lede, best, hint, secondary)) t.setTextColor(inkSoft)

        primary.background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(40, Color.red(ink), Color.green(ink), Color.blue(ink))),
            GradientDrawable().apply {
                cornerRadius = 999 * dp
                setStroke((2 * dp).roundToInt(), ink)
            },
            GradientDrawable().apply { cornerRadius = 999 * dp; setColor(Color.BLACK) }
        )
        for (b in listOf(pauseButton, soundButton, hapticsButton)) {
            b.imageTintList = ColorStateList.valueOf(ink)
            b.background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(50, Color.red(ink), Color.green(ink), Color.blue(ink))),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke((1.5f * dp).roundToInt(), ink)
                },
                null
            )
        }
    }

    private fun fade(view: View, show: Boolean, ms: Long) {
        view.animate().cancel()
        view.animate().alpha(if (show) 1f else 0f).setDuration(if (reduceMotion) 0 else ms).start()
    }

    private fun popCombo() {
        comboAnim?.cancel()
        val lift = 6 * dp
        if (reduceMotion) {
            // Animators finish instantly when system animations are off, so just show, then hide.
            handler.removeCallbacks(hideCombo)
            comboText.alpha = 1f; comboText.translationY = 0f; comboText.scaleX = 1f; comboText.scaleY = 1f
            handler.postDelayed(hideCombo, 1100)
            return
        }
        comboAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(1100).apply {
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val p = it.animatedValue as Float
                val (a, y, s) = when {
                    p < 0.15f -> { val k = p / 0.15f; Triple(k, lift * (1 - k), 0.9f + 0.16f * k) }
                    p < 0.6f -> { val k = (p - 0.15f) / 0.45f; Triple(1f, 0f, 1.06f - 0.06f * k) }
                    else -> { val k = (p - 0.6f) / 0.4f; Triple(1 - k, -lift * k, 1f) }
                }
                comboText.alpha = a
                comboText.translationY = y
                comboText.scaleX = s; comboText.scaleY = s
            }
            start()
        }
    }

    private companion object {
        val INK_DARK = Color.rgb(0x2B, 0x21, 0x40)
        val INK_LIGHT = Color.rgb(0xFF, 0xF6, 0xEC)
        val INK_SOFT_DARK = Color.argb(179, 0x2B, 0x21, 0x40)
        val INK_SOFT_LIGHT = Color.argb(184, 0xFF, 0xF6, 0xEC)
    }
}
