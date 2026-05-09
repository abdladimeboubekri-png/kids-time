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
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var storage: Storage
    private lateinit var statusUsage: TextView
    private lateinit var statusOverlay: TextView
    private lateinit var kidsContainer: LinearLayout

    private val emojiOptions = listOf(
        "🦁", "🐼", "🦋", "🐰", "🦊", "🐻", "🐯", "🦄", "🐶", "🐱",
        "🦉", "🐢", "🐳", "🦒", "🐧", "🦘", "🐨", "🦝", "🐹", "🦇"
    )

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
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        }
        findViewById<Button>(R.id.btnPickApps).setOnClickListener { pickApps() }
        findViewById<Button>(R.id.btnAddKid).setOnClickListener { showKidDialog(null) }
        findViewById<Button>(R.id.btnAdminPin).setOnClickListener { changeAdminPin() }
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
        if (hasUsage && hasOverlay) MonitorService.start(this)
        renderKids()
    }

    private fun renderKids() {
        kidsContainer.removeAllViews()
        val kids = storage.getKids()
        val inflater = LayoutInflater.from(this)
        kids.forEach { kid ->
            val v = inflater.inflate(R.layout.item_kid, kidsContainer, false)
            v.findViewById<TextView>(R.id.kidEmoji).text = kid.emoji
            v.findViewById<TextView>(R.id.kidName).text = kid.name
            val left = (kid.dailyLimitSec - kid.usedSec).coerceAtLeast(0)
            v.findViewById<TextView>(R.id.kidTime).text =
                "${formatTime(left)} left of ${formatTime(kid.dailyLimitSec)}"
            val pct = (kid.usedSec.toFloat() / kid.dailyLimitSec.toFloat() * 100f).coerceIn(0f, 100f)
            v.findViewById<ProgressBar>(R.id.kidProgress).progress = pct.toInt()

            v.findViewById<Button>(R.id.kidEdit).setOnClickListener { showKidDialog(kid) }
            v.findViewById<Button>(R.id.kidReset).setOnClickListener {
                kid.usedSec = 0
                storage.saveKids(kids)
                renderKids()
            }
            v.findViewById<Button>(R.id.kidDelete).setOnClickListener { confirmDelete(kid) }
            kidsContainer.addView(v)
        }

        if (kids.isEmpty()) {
            val empty = TextView(this)
            empty.text = "No kids added yet. Tap + Add Kid below."
            empty.setTextColor(0xFF7a82a8.toInt())
            empty.setPadding(20, 20, 20, 20)
            kidsContainer.addView(empty)
        }
    }

    private fun showKidDialog(existing: Kid?) {
        val view = layoutInflater.inflate(R.layout.dialog_kid, null)
        val nameEt = view.findViewById<EditText>(R.id.dlgName)
        val pinEt = view.findViewById<EditText>(R.id.dlgPin)
        val limitEt = view.findViewById<EditText>(R.id.dlgLimit)
        val emojiSpinner = view.findViewById<Spinner>(R.id.dlgEmoji)

        // Populate emoji spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, emojiOptions)
        emojiSpinner.adapter = adapter

        if (existing != null) {
            nameEt.setText(existing.name)
            pinEt.setText(existing.pin)
            limitEt.setText((existing.dailyLimitSec / 3600.0).toString())
            val idx = emojiOptions.indexOf(existing.emoji)
            if (idx >= 0) emojiSpinner.setSelection(idx)
        } else {
            limitEt.setText("3")
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add a Kid" else "Edit ${existing.name}")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = nameEt.text.toString().trim()
                val pin = pinEt.text.toString().trim()
                val emoji = emojiOptions[emojiSpinner.selectedItemPosition]
                val hours = limitEt.text.toString().toDoubleOrNull() ?: 3.0
                val limitSec = (hours * 3600).toLong()

                if (name.isEmpty()) {
                    toast("Name cannot be empty"); return@setPositiveButton
                }
                if (pin.length != 4 || !pin.all { it.isDigit() }) {
                    toast("PIN must be exactly 4 digits"); return@setPositiveButton
                }
                if (limitSec < 60) {
                    toast("Time limit too short"); return@setPositiveButton
                }

                if (existing == null) {
                    storage.addKid(name, emoji, pin, limitSec)
                    toast("Added $name!")
                } else {
                    val kids = storage.getKids()
                    kids.find { it.id == existing.id }?.apply {
                        this.name = name
                        this.pin = pin
                        this.emoji = emoji
                        this.dailyLimitSec = limitSec
                    }
                    storage.saveKids(kids)
                    toast("Updated $name!")
                }
                renderKids()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(kid: Kid) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${kid.name}?")
            .setMessage("This will permanently remove ${kid.name} and their time data.")
            .setPositiveButton("Delete") { _, _ ->
                storage.removeKid(kid.id)
                renderKids()
                toast("${kid.name} removed")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun changeAdminPin() {
        val view = layoutInflater.inflate(R.layout.dialog_admin_pin, null)
        val newPinEt = view.findViewById<EditText>(R.id.newAdminPin)
        newPinEt.setText(storage.getAdminPin())
        AlertDialog.Builder(this)
            .setTitle("Change Admin PIN")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val pin = newPinEt.text.toString()
                if (pin.length == 4 && pin.all { it.isDigit() }) {
                    storage.setAdminPin(pin)
                    toast("Admin PIN updated")
                } else toast("PIN must be 4 digits")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickApps() {
        val pm = packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                it.packageName.contains("youtube") ||
                it.packageName.contains("netflix") ||
                it.packageName.contains("disney") ||
                it.packageName.contains("spotify") ||
                it.packageName.contains("twitch") ||
                it.packageName.contains("amazon")
            }
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
                toast("Locked apps updated")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hasUsageStats(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun formatTime(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
