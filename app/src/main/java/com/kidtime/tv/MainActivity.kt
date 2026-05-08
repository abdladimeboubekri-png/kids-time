package com.kidtime.tv

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var storage: Storage
    private lateinit var statusUsage: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var kidsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        storage = Storage(this)

        statusUsage = findViewById(R.id.statusUsage)
        statusOverlay = findViewById(R.id.statusOverlay)
        kidsContainer = findViewById(R.id.kidsContainer)

        findViewById<Button>(R.id.btnUsage).setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }
        findViewById<Button>(R.id.btnPickApps).setOnClickListener { pickApps() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { openSettingsDialog() }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            val i = Intent(this, LockActivity::class.java)
            i.putExtra("blocked_package", "test")
            startActivity(i)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val hasUsage = hasUsageStats()
        val hasOverlay = hasOverlayPermission()
        statusUsage.text = if (hasUsage) "✅ Usage Access: Granted" else "❌ Usage Access: NOT granted"
        statusOverlay.text = if (hasOverlay) "✅ Overlay Permission: Granted" else "❌ Overlay Permission: NOT granted"

        if (hasUsage && hasOverlay) {
            MonitorService.start(this)
        }

        renderKids()
    }

    private fun renderKids() {
        kidsContainer.removeAllViews()
        val kids = storage.getKids()
        val limit = storage.getDailyLimitSec()
        kids.forEach { kid ->
            val v = layoutInflater.inflate(R.layout.item_kid, kidsContainer, false)
            v.findViewById<TextView>(R.id.kidEmoji).text = kid.emoji
            v.findViewById<TextView>(R.id.kidName).text = kid.name
            val left = (limit - kid.usedSec).coerceAtLeast(0)
            v.findViewById<TextView>(R.id.kidTime).text =
                "${formatTime(left)} left of ${formatTime(limit)}"
            val pct = (kid.usedSec.toFloat() / limit.toFloat() * 100f).coerceIn(0f, 100f)
            v.findViewById<ProgressBar>(R.id.kidProgress).progress = pct.toInt()
            v.findViewById<Button>(R.id.kidReset).setOnClickListener {
                kid.usedSec = 0
                storage.saveKids(kids)
                renderKids()
            }
            kidsContainer.addView(v)
        }
    }

    private fun openSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val kids = storage.getKids()

        val name1 = view.findViewById<EditText>(R.id.name1)
        val pin1 = view.findViewById<EditText>(R.id.pin1)
        val name2 = view.findViewById<EditText>(R.id.name2)
        val pin2 = view.findViewById<EditText>(R.id.pin2)
        val name3 = view.findViewById<EditText>(R.id.name3)
        val pin3 = view.findViewById<EditText>(R.id.pin3)
        val limitHrs = view.findViewById<EditText>(R.id.limitHrs)
        val adminPin = view.findViewById<EditText>(R.id.adminPin)

        name1.setText(kids[0].name); pin1.setText(kids[0].pin)
        name2.setText(kids[1].name); pin2.setText(kids[1].pin)
        name3.setText(kids[2].name); pin3.setText(kids[2].pin)
        limitHrs.setText((storage.getDailyLimitSec() / 3600.0).toString())
        adminPin.setText(storage.getAdminPin())

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                kids[0].name = name1.text.toString(); kids[0].pin = pin1.text.toString()
                kids[1].name = name2.text.toString(); kids[1].pin = pin2.text.toString()
                kids[2].name = name3.text.toString(); kids[2].pin = pin3.text.toString()
                storage.saveKids(kids)
                val hrs = limitHrs.text.toString().toDoubleOrNull() ?: 3.0
                storage.setDailyLimitSec((hrs * 3600).toLong())
                storage.setAdminPin(adminPin.text.toString())
                Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickApps() {
        val pm = packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.packageName.contains("youtube") || it.packageName.contains("netflix") || it.packageName.contains("disney") }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        val labels = installed.map { "${pm.getApplicationLabel(it)} (${it.packageName})" }.toTypedArray()
        val packages = installed.map { it.packageName }
        val blocked = storage.getBlockedPackages()
        val checked = packages.map { blocked.contains(it) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Choose apps to lock")
            .setMultiChoiceItems(labels, checked) { _, i, isChecked -> checked[i] = isChecked }
            .setPositiveButton("Save") { _, _ ->
                val newSet = mutableSetOf<String>()
                packages.forEachIndexed { i, p -> if (checked[i]) newSet.add(p) }
                storage.setBlockedPackages(newSet)
                Toast.makeText(this, "Locked apps updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hasUsageStats(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun formatTime(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
