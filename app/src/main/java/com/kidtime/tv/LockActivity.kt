package com.kidtime.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class LockActivity : AppCompatActivity() {

    private lateinit var storage: Storage
    private lateinit var pinDots: List<TextView>
    private lateinit var kidButtons: List<Button>
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private var pinBuffer = StringBuilder()
    private var selectedKidId: Int = -1
    private var blockedPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)
        storage = Storage(this)
        blockedPackage = intent.getStringExtra("blocked_package")

        titleText = findViewById(R.id.title)
        statusText = findViewById(R.id.status)
        pinDots = listOf(
            findViewById(R.id.dot1), findViewById(R.id.dot2),
            findViewById(R.id.dot3), findViewById(R.id.dot4)
        )
        kidButtons = listOf(
            findViewById(R.id.kid1), findViewById(R.id.kid2), findViewById(R.id.kid3)
        )

        renderKids()
        setupNumpad()

        // Make this fullscreen and on top
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    private fun renderKids() {
        val kids = storage.getKids()
        val limit = storage.getDailyLimitSec()
        kids.forEachIndexed { idx, kid ->
            if (idx >= kidButtons.size) return@forEachIndexed
            val btn = kidButtons[idx]
            val left = (limit - kid.usedSec).coerceAtLeast(0)
            val timeStr = formatTime(left)
            btn.text = "${kid.emoji} ${kid.name}\n$timeStr left"
            btn.isEnabled = left > 0
            btn.alpha = if (left > 0) 1f else 0.4f
            btn.setOnClickListener {
                selectedKidId = kid.id
                pinBuffer.clear()
                updateDots()
                kidButtons.forEachIndexed { i, b ->
                    b.setBackgroundResource(
                        if (i == idx) R.drawable.btn_kid_selected else R.drawable.btn_kid
                    )
                }
                statusText.text = "${kid.name}, enter your PIN"
            }
        }
    }

    private fun setupNumpad() {
        val ids = listOf(R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4,
            R.id.n5, R.id.n6, R.id.n7, R.id.n8, R.id.n9)
        ids.forEachIndexed { num, id ->
            findViewById<Button>(id).setOnClickListener { onDigit(num) }
        }
        findViewById<Button>(R.id.del).setOnClickListener { onBackspace() }
        findViewById<Button>(R.id.exit).setOnClickListener { goHome() }
    }

    private fun onDigit(d: Int) {
        if (selectedKidId < 0) {
            statusText.text = "⬆️ Pick your name first"
            return
        }
        if (pinBuffer.length >= 4) return
        pinBuffer.append(d)
        updateDots()
        if (pinBuffer.length == 4) Handler(Looper.getMainLooper()).postDelayed({ checkPin() }, 200)
    }

    private fun onBackspace() {
        if (pinBuffer.isNotEmpty()) pinBuffer.deleteCharAt(pinBuffer.length - 1)
        updateDots()
    }

    private fun updateDots() {
        pinDots.forEachIndexed { i, dot ->
            dot.text = if (i < pinBuffer.length) "●" else "○"
        }
    }

    private fun checkPin() {
        val kids = storage.getKids()
        val kid = kids.find { it.id == selectedKidId } ?: return
        if (pinBuffer.toString() == kid.pin) {
            val left = storage.getDailyLimitSec() - kid.usedSec
            if (left <= 0) {
                statusText.text = "❌ ${kid.name}'s time is up today"
                pinBuffer.clear(); updateDots()
                return
            }
            // Mark this kid active and let them through
            storage.setActiveKid(kid.id)
            Toast.makeText(this, "Welcome ${kid.emoji} ${kid.name}!", Toast.LENGTH_SHORT).show()
            finish()
            // Let user back to the blocked app -- bring it to foreground
            blockedPackage?.let {
                val launch = packageManager.getLaunchIntentForPackage(it)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                }
            }
        } else {
            statusText.text = "❌ Wrong PIN, try again"
            pinBuffer.clear(); updateDots()
        }
    }

    private fun goHome() {
        // Kid pressed exit -> go to launcher
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
        finish()
    }

    // Block back button - kids must pick a kid + enter PIN, or hit exit
    override fun onBackPressed() {
        // Just go home instead of dismissing
        goHome()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Map remote control numeric keys
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            onDigit(keyCode - KeyEvent.KEYCODE_0)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) { onBackspace(); return true }
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
