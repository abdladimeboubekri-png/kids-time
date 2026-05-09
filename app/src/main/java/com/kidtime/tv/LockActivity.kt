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
    private lateinit var kidsContainer: LinearLayout

    private lateinit var rowContainers: List<LinearLayout>
    private lateinit var rowDigits: List<List<TextView>>

    // Bottom row: left = backspace, middle = 0, right = OK/submit
    private val digitGrid = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("\u232B", "0", "\u2713")
    )

    private var selectedRow: Int = 0
    private var pinBuffer = StringBuilder()
    private var selectedKidId: Int = -1
    private var blockedPackage: String? = null
    private var kidViewMap: MutableMap<Int, View> = mutableMapOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)
        storage = Storage(this)
        blockedPackage = intent.getStringExtra("blocked_package")

        titleText = findViewById(R.id.title)
        statusText = findViewById(R.id.status)
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
                tv.setOnClickListener {
                    selectedRow = ri
                    highlightRow()
                    pickColumn(ci)
                }
            }
        }

        renderKids()
        highlightRow()

        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        statusText.text = "Pick your name"
    }

    private fun renderKids() {
        kidsContainer.removeAllViews()
        kidViewMap.clear()
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
            card.isEnabled = left > 0

            card.setOnClickListener {
                if (left <= 0) return@setOnClickListener
                onKidSelected(kid.id)
            }

            kidsContainer.addView(card)
            kidViewMap[kid.id] = card
        }
    }

    private fun onKidSelected(id: Int) {
        selectedKidId = id
        pinBuffer.clear()
        updateDots()
        // Update card backgrounds
        kidViewMap.forEach { (kidId, view) ->
            view.setBackgroundResource(
                if (kidId == id) R.drawable.btn_kid_selected else R.drawable.btn_kid
            )
        }
        val kid = storage.getKids().find { it.id == id }
        statusText.text = "${kid?.name ?: "?"}, enter your PIN"
    }

    private fun highlightRow() {
        rowContainers.forEachIndexed { i, container ->
            container.setBackgroundResource(
                if (i == selectedRow) R.drawable.row_selected else R.drawable.row_normal
            )
        }
    }

    private fun pickColumn(col: Int) {
        if (selectedKidId < 0) {
            statusText.text = "\u2192 Pick your name first"
            return
        }
        val cell = digitGrid[selectedRow][col]
        when (cell) {
            "\u232B" -> onBackspace()
            "\u2713" -> {
                if (pinBuffer.length == 4) checkPin()
                else statusText.text = "Enter all 4 digits first"
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
        if (pinBuffer.isNotEmpty()) pinBuffer.deleteCharAt(pinBuffer.length - 1)
        updateDots()
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
            Toast.makeText(this, "Welcome ${kid.emoji} ${kid.name}!", Toast.LENGTH_SHORT).show()
            finish()
            blockedPackage?.let {
                if (it != "test") {
                    val launch = packageManager.getLaunchIntentForPackage(it)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launch)
                    }
                }
            }
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
        goHome()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (selectedKidId >= 0) {
                    selectedRow = (selectedRow - 1 + rowContainers.size) % rowContainers.size
                    highlightRow()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (selectedKidId >= 0) {
                    selectedRow = (selectedRow + 1) % rowContainers.size
                    highlightRow()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (selectedKidId >= 0) { pickColumn(0); return true }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (selectedKidId >= 0) { pickColumn(1); return true }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (selectedKidId >= 0) { pickColumn(2); return true }
            }
            KeyEvent.KEYCODE_DEL -> { onBackspace(); return true }
        }
        return super.onKeyDown(keyCode, event)
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
