package com.kidtime.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class LockActivity : AppCompatActivity() {

    private lateinit var storage: Storage
    private lateinit var pinDots: List<TextView>
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var hintText: TextView
    private lateinit var kidsContainer: LinearLayout
    private lateinit var kidsScrollView: ScrollView

    private lateinit var rowContainers: List<LinearLayout>
    private lateinit var rowDigits: List<List<TextView>>

    // Bottom row: left = backspace, middle = 0, right = BACK (return arrow)
    private val digitGrid = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("\u232B", "0", "\u21A9")
    )

    private enum class Mode { KIDS, PIN }
    private var mode: Mode = Mode.KIDS

    private var selectedRow: Int = 0
    private var selectedKidIndex: Int = 0
    private var pinBuffer = StringBuilder()
    private var selectedKidId: Int = -1
    private var blockedPackage: String? = null
    private var kidViews: MutableList<View> = mutableListOf()

    // Debounce: ignore key presses that come too fast in a row
    // Mi TV Stick remote auto-repeats while held — without this, holding ⬆️
    // briefly skips 5 kids at once. 220ms is comfortable for both single
    // press and intentional multi-press, but rejects spam from auto-repeat.
    private var lastKeyTime: Long = 0L
    private val keyDebounceMs: Long = 220L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)
        storage = Storage(this)
        blockedPackage = intent.getStringExtra("blocked_package")

        titleText = findViewById(R.id.title)
        statusText = findViewById(R.id.status)
        hintText = findViewById(R.id.hint)
        kidsContainer = findViewById(R.id.kidsContainer)
        kidsScrollView = findViewById(R.id.kidsScrollView)
        pinDots = listOf(
            findViewById(R.id.dot1), findViewById(R.id.dot2),
            findViewById(R.id.dot3), findViewById(R.id.dot4)
        )

        rowContainers = listOf(
            findViewById(R.id.row0),
            findViewById(R.id.row1),
            findViewById(R.id.row2),
            findViewById(R.id.row3)
        )
        rowDigits = listOf(
            listOf(findViewById(R.id.r0c0), findViewById(R.id.r0c1), findViewById(R.id.r0c2)),
            listOf(findViewById(R.id.r1c0), findViewById(R.id.r1c1), findViewById(R.id.r1c2)),
            listOf(findViewById(R.id.r2c0), findViewById(R.id.r2c1), findViewById(R.id.r2c2)),
            listOf(findViewById(R.id.r3c0), findViewById(R.id.r3c1), findViewById(R.id.r3c2))
        )

        rowDigits.forEachIndexed { ri, row ->
            row.forEachIndexed { ci, tv ->
                tv.text = digitGrid[ri][ci]
            }
        }

        // CRITICAL: prevent the ScrollView from stealing up/down D-pad events.
        // Without this, on Mi TV Stick the up/down keys scroll the kid list
        // pixel-by-pixel instead of jumping to the next kid card.
        kidsScrollView.isFocusable = false
        kidsScrollView.isFocusableInTouchMode = false
        kidsScrollView.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS

        renderKids()
        enterKidsMode()

        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    // ============== KID LIST RENDERING ==============
    private fun renderKids() {
        kidsContainer.removeAllViews()
        kidViews.clear()
        val kids = storage.getKids()
        val inflater = LayoutInflater.from(this)
        kids.forEach { kid ->
            val card = inflater.inflate(R.layout.lock_kid_card, kidsContainer, false)
            val left = (kid.dailyLimitSec - kid.usedSec).coerceAtLeast(0)
            card.findViewById<TextView>(R.id.kidEmoji).text = kid.emoji
            card.findViewById<TextView>(R.id.kidName).text = kid.name
            card.findViewById<TextView>(R.id.kidTime).text =
                if (left > 0) "${formatTime(left)} left" else "Time's up today"
            card.alpha = if (left > 0) 1f else 0.4f
            card.tag = kid.id
            // Make the card non-focusable so the system focus engine can't
            // steal events from us
            card.isFocusable = false
            card.isClickable = false
            kidsContainer.addView(card)
            kidViews.add(card)
        }
    }

    // ============== MODE TRANSITIONS ==============
    private fun enterKidsMode() {
        mode = Mode.KIDS
        selectedKidId = -1
        pinBuffer.clear()
        updateDots()
        statusText.text = "Pick your name"
        hintText.text = "\u2B06\uFE0F \u2B07\uFE0F navigate  \u00B7  \u23FA OK to select"
        clearRowHighlight()
        if (kidViews.isNotEmpty()) {
            val kids = storage.getKids()
            val firstAvailable = kids.indexOfFirst {
                (it.dailyLimitSec - it.usedSec) > 0
            }
            selectedKidIndex = if (firstAvailable >= 0) firstAvailable else 0
            highlightKid(selectedKidIndex)
        }
    }

    private fun enterPinMode() {
        mode = Mode.PIN
        pinBuffer.clear()
        updateDots()
        selectedRow = 0
        highlightRow()
        kidViews.forEachIndexed { _, view ->
            view.setBackgroundResource(
                if (view.tag == selectedKidId) R.drawable.btn_kid_active
                else R.drawable.btn_kid
            )
        }
        val kid = storage.getKids().find { it.id == selectedKidId }
        statusText.text = "${kid?.emoji} ${kid?.name}, enter your PIN"
        hintText.text = "\u2B06\uFE0F \u2B07\uFE0F row  \u00B7  \u2B05\uFE0F \u23FA \u27A1\uFE0F pick number"
    }

    // ============== HIGHLIGHTS ==============
    private fun highlightKid(idx: Int) {
        kidViews.forEachIndexed { i, view ->
            view.setBackgroundResource(
                if (i == idx) R.drawable.btn_kid_focused else R.drawable.btn_kid
            )
        }
        // Smooth-scroll the focused card into view inside its ScrollView
        val focused = kidViews.getOrNull(idx) ?: return
        focused.post {
            val cardTop = focused.top
            val cardBottom = focused.bottom
            val visibleTop = kidsScrollView.scrollY
            val visibleBottom = visibleTop + kidsScrollView.height
            when {
                cardTop < visibleTop -> kidsScrollView.smoothScrollTo(0, cardTop - 20)
                cardBottom > visibleBottom -> kidsScrollView.smoothScrollTo(0, cardBottom - kidsScrollView.height + 20)
            }
        }
    }

    private fun highlightRow() {
        rowContainers.forEachIndexed { i, container ->
            container.setBackgroundResource(
                if (i == selectedRow) R.drawable.row_selected else R.drawable.row_normal
            )
        }
    }

    private fun clearRowHighlight() {
        rowContainers.forEach { container ->
            container.setBackgroundResource(R.drawable.row_normal)
        }
    }

    // ============== INPUT HANDLERS ==============
    private fun onKidConfirmed() {
        val kids = storage.getKids()
        val kid = kids.getOrNull(selectedKidIndex) ?: return
        val left = kid.dailyLimitSec - kid.usedSec
        if (left <= 0) {
            statusText.text = "\u274C ${kid.name}'s time is up today"
            return
        }
        selectedKidId = kid.id
        enterPinMode()
    }

    private fun pickColumn(col: Int) {
        if (mode != Mode.PIN) return
        val cell = digitGrid[selectedRow][col]
        when (cell) {
            "\u232B" -> onBackspace()
            "\u21A9" -> enterKidsMode()
            else -> {
                val digit = cell.toIntOrNull() ?: return
                onDigit(digit)
            }
        }
    }

    private fun onDigit(d: Int) {
        if (pinBuffer.length >= 4) return
        pinBuffer.append(d)
        updateDots()
        if (pinBuffer.length == 4) Handler(Looper.getMainLooper()).postDelayed({ checkPin() }, 250)
    }

    private fun onBackspace() {
        if (pinBuffer.isNotEmpty()) {
            pinBuffer.deleteCharAt(pinBuffer.length - 1)
            updateDots()
        }
    }

    private fun updateDots() {
        pinDots.forEachIndexed { i, dot ->
            dot.text = if (i < pinBuffer.length) "\u25CF" else "\u25CB"
        }
    }

    private fun checkPin() {
        val kids = storage.getKids()
        val kid = kids.find { it.id == selectedKidId } ?: return
        if (pinBuffer.toString() == kid.pin) {
            val left = kid.dailyLimitSec - kid.usedSec
            if (left <= 0) {
                statusText.text = "\u274C ${kid.name}'s time is up today"
                pinBuffer.clear()
                updateDots()
                return
            }
            storage.setActiveKid(kid.id)
            storage.setLockGraceUntil(System.currentTimeMillis() + 8_000L)
            Toast.makeText(this, "Welcome ${kid.emoji} ${kid.name}!", Toast.LENGTH_SHORT).show()
            blockedPackage?.let {
                if (it != "test") {
                    val launch = packageManager.getLaunchIntentForPackage(it)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launch)
                    }
                }
            }
            finish()
        } else {
            statusText.text = "\u274C Wrong PIN, try again"
            pinBuffer.clear()
            updateDots()
        }
    }

    private fun goHome() {
        storage.setActiveKid(-1)
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode == Mode.PIN) enterKidsMode() else goHome()
    }

    // ============== D-PAD HANDLING ==============
    // Use dispatchKeyEvent at the top of the input chain so we intercept
    // EVERY key event before any view (ScrollView, focus engine, etc.)
    // can swallow it.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }

        // Reject auto-repeat events. When you hold the D-pad button on the
        // Mi TV Stick, the system fires repeated KEYDOWN events with
        // getRepeatCount() > 0 — these would skip across multiple kids
        // / multiple rows in one button-hold.
        if (event.repeatCount > 0) return true

        // Time-based debounce as a second line of defense for remotes
        // that send rapid bursts on a single click.
        val now = System.currentTimeMillis()
        if (now - lastKeyTime < keyDebounceMs) return true
        lastKeyTime = now

        // Route to mode-specific handler
        val handled = when (mode) {
            Mode.KIDS -> handleKidsModeKey(event.keyCode)
            Mode.PIN -> handlePinModeKey(event.keyCode)
        }
        // If we didn't handle it, fall through (so BACK still works etc.)
        return if (handled) true else super.dispatchKeyEvent(event)
    }

    private fun handleKidsModeKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (kidViews.isEmpty()) return true
                selectedKidIndex = (selectedKidIndex - 1 + kidViews.size) % kidViews.size
                highlightKid(selectedKidIndex)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (kidViews.isEmpty()) return true
                selectedKidIndex = (selectedKidIndex + 1) % kidViews.size
                highlightKid(selectedKidIndex)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                onKidConfirmed()
                return true
            }
        }
        return false
    }

    private fun handlePinModeKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                selectedRow = (selectedRow - 1 + rowContainers.size) % rowContainers.size
                highlightRow()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                selectedRow = (selectedRow + 1) % rowContainers.size
                highlightRow()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { pickColumn(0); return true }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> { pickColumn(1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { pickColumn(2); return true }
            KeyEvent.KEYCODE_DEL -> { onBackspace(); return true }
        }
        return false
    }

    private fun formatTime(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }
}
