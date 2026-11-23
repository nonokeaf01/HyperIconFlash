package com.example.hypericons

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.*
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var spinner: Spinner
    private lateinit var pkgOverride: EditText
    private lateinit var dirEdit: EditText
    private lateinit var sizeEdit: EditText
    private lateinit var trimBox: CheckBox
    private lateinit var keepBox: CheckBox
    private lateinit var logView: TextView
    private lateinit var runBtn: Button
    private lateinit var restoreBtn: Button
    private var packs: List<Pair<String, String>> = emptyList()

    private val onBinder = Shizuku.OnBinderReceivedListener { runOnUiThread { refreshStatus() } }
    private val onDead = Shizuku.OnBinderDeadListener { runOnUiThread { refreshStatus() } }
    private val onPerm = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refreshStatus() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, dp(14), 0, dp(4)) }

        status = TextView(this)
        val grant = Button(this).apply {
            text = "Grant Shizuku permission"
            setOnClickListener {
                if (!Shizuku.pingBinder()) toast("Start Shizuku first (Shizuku app → Start via wireless debugging)")
                else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) refreshStatus()
                else Shizuku.requestPermission(100)
            }
        }
        spinner = Spinner(this)
        pkgOverride = EditText(this).apply { hint = "…or type icon pack package name" }
        dirEdit = EditText(this).apply {
            setText("/sdcard/Android/data/com.android.thememanager/files/MIUI/theme/.data/content/icons")
        }
        sizeEdit = EditText(this).apply {
            hint = "auto"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        trimBox = CheckBox(this).apply { text = "Trim padding + resize to the theme's icon size"; isChecked = true }
        keepBox = CheckBox(this).apply { text = "Keep the theme's masks/borders (res/ extras)" }
        runBtn = Button(this).apply { text = "Build & flash into dummy theme"; setOnClickListener { start(false) } }
        restoreBtn = Button(this).apply { text = "Restore backup"; setOnClickListener { start(true) } }
        logView = TextView(this).apply { setTextIsSelectable(true); setPadding(0, dp(12), 0, 0) }

        root.addView(status); root.addView(grant)
        root.addView(label("Icon pack")); root.addView(spinner); root.addView(pkgOverride)
        root.addView(label("Theme icons folder")); root.addView(dirEdit)
        root.addView(label("Icon size px (blank = detect from theme)")); root.addView(sizeEdit)
        root.addView(trimBox); root.addView(keepBox)
        root.addView(runBtn); root.addView(restoreBtn); root.addView(logView)
        setContentView(ScrollView(this).apply { addView(root); fitsSystemWindows = true })

        packs = findIconPacks()
        val labels = if (packs.isEmpty()) listOf("No icon packs found") else packs.map { it.second }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onDead)
        Shizuku.addRequestPermissionResultListener(onPerm)
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(onBinder)
        Shizuku.removeBinderDeadListener(onDead)
        Shizuku.removeRequestPermissionResultListener(onPerm)
    }

    private fun refreshStatus() {
        status.text = when {
            !Shizuku.pingBinder() -> "Shizuku: not running (open Shizuku app → Start via wireless debugging)"
            Shizuku.isPreV11() -> "Shizuku: unsupported version (update Shizuku)"
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "Shizuku: ready ✓"
            else -> "Shizuku: running — tap Grant Shizuku permission"
        }
    }

    private fun findIconPacks(): List<Pair<String, String>> {
        val actions = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.anddoes.launcher.THEME",
            "com.fede.launcher.THEME_ICONPACK",
            "home.solo.launcher.free.THEMES",
            "org.adw.launcher.icons.ACTION_PICK_ICON"
        )
        val seen = LinkedHashMap<String, String>()
        for (action in actions) {
            val resolved = packageManager.queryIntentActivities(Intent(action), 0)
            for (ri in resolved) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (!seen.containsKey(pkg)) {
                    val label = try {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                    seen[pkg] = "$label ($pkg)"
                }
            }
        }
        return seen.entries.sortedBy { it.value.lowercase() }.map { it.key to it.value }
    }

    private fun start(isRestore: Boolean) {
        if (!Shizuku.pingBinder()) {
            toast("Start Shizuku first")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(100)
            return
        }
        val selectedPkg = pkgOverride.text.toString().trim().ifEmpty {
            packs.getOrNull(spinner.selectedItemPosition)?.first.orEmpty()
        }
        val iconsDir = dirEdit.text.toString().trim().trimEnd('/')
        val trim = trimBox.isChecked
        val keepExtras = keepBox.isChecked
        val manualSize = sizeEdit.text.toString().trim().toIntOrNull()

        setBusy(true)
        logView.text = ""
        val appendLog: (String) -> Unit = { line ->
            runOnUiThread {
                logView.append(line + "\n")
            }
        }

        Thread {
            try {
                if (isRestore) {
                    Pipeline.restore(this, iconsDir, appendLog)
                } else {
                    Pipeline.run(this, selectedPkg, iconsDir, trim, keepExtras, manualSize, appendLog)
                }
            } catch (t: Throwable) {
                appendLog("ERROR: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                runOnUiThread { setBusy(false) }
            }
        }.start()
    }

    private fun setBusy(busy: Boolean) {
        runBtn.isEnabled = !busy
        restoreBtn.isEnabled = !busy
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
