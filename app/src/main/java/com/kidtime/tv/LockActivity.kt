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

    private lateinit var rowContainers: List<LinearLayout>
    private lateinit var rowDigits: List<List<TextView>>

    // Bottom row: left = backspace, middle = 0, right = BACK (to kid picker)
    private val digitGrid = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("\u232B", "0", "\u21A9")  // Backspace, 0, Return-arrow
    )

    // Two input modes:
    //   MODE_KIDS = D-pad picks between kid cards
    //   MODE_PIN  = D-pad locked to numpad rows; left/center/right pick column
    private enum class Mode { KIDS, PIN }
    private var mode: Mode = Mode.KIDS

    private var selectedRow: Int = 0
    private var selectedKidIndex: Int = 0   // index of kid being focused in KIDS mode
    private var pinBuffer = StringBuilder()
    private var selectedKidId: Int = -1
    private var blockedPackage: String? = null
    private var kidViews: MutableList<View> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)
        storage = Storage(this)
        blockedPackage = intent.getStringExtra("blocked_package")

        titleText = findViewById(R.id.title)
        statusText = findViewById(R.id.status)
        hintText = findViewById(R.id.hint)
        kidsContainer = findViewById(R.id.kidsContainer)
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
        hintText.text = "\u2B05\uFE0F  \u2B07\uFE0F  \u2B06\uFE0F  \u27A1\uFE0F  to navigate  \u00B7  \u23FA select"
        // Clear numpad row highlight
        clearRowHighlight()
        // Highlight first available kid
        if (kidViews.isNotEmpty()) {
            // Find first kid that has time left
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
        // Keep selected kid card highlighted with the "active" style
        kidViews.forEachIndexed { i, view ->
            view.setBackgroundResource(
                if (view.tag == selectedKidId) R.drawable.btn_kid_active
                else R.drawable.btn_kid
            )
        }
        val kid = storage.getKids().find { it.id == selectedKidId }
        statusText.text = "${kid?.emoji} ${kid?.name}, enter your PIN"
        hintText.text = "\u2B06\uFE0F  \u2B07\uFE0F  choose row  \u00B7  \u2B05\uFE0F  \u23FA  \u27A1\uFE0F  pick number"
    }

    // ============== HIGHLIGHTS ==============
    private fun highlightKid(idx: Int) {
        kidViews.forEachIndexed { i, view ->
            view.setBackgroundResource(
                if (i == idx) R.drawable.btn_kid_focused else R.drawable.btn_kid
            )
        }
        // Scroll the focused kid into view
        val focused = kidViews.getOrNull(idx)
        focused?.let {
            kidsContainer.parent?.let { parent ->
                if (parent is android.widget.ScrollView) {
                    val y = it.top
                    parent.smoothScrollTo(0, y - 40)
                }
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
            "\u21A9" -> {
                // Return to kid picker
                enterKidsMode()
            }
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
            // SUCCESS: mark active and grant a "grace period" so MonitorService
            // doesn't immediately re-trigger the lock when the blocked app
            // launches in the next instant.
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
        if (mode == Mode.PIN) {
            // Back returns to kid picker instead of leaving the screen
            enterKidsMode()
        } else {
            goHome()
        }
    }

    // ============== D-PAD HANDLING ==============
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (mode) {
            Mode.KIDS -> handleKidsModeKey(keyCode)
            Mode.PIN -> handlePinModeKey(keyCode)
        }
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
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
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
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { pickColumn(1); return true }
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
