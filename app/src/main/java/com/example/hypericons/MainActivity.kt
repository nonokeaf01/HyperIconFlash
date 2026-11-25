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
    private lateinit var themeSpinner: Spinner
    private lateinit var refreshThemesBtn: Button
    private lateinit var sizeEdit: EditText
    private lateinit var trimBox: CheckBox
    private lateinit var keepBox: CheckBox
    private lateinit var logView: TextView
    private lateinit var mtzBtn: Button
    private lateinit var runBtn: Button
    private lateinit var restoreBtn: Button
    private var packs: List<Pair<String, String>> = emptyList()
    private var themes: List<Pair<String, String>> = emptyList()

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
        sizeEdit = EditText(this).apply {
            hint = "auto (266px for .mtz / theme size for flash)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        trimBox = CheckBox(this).apply { text = "Trim padding + resize icons"; isChecked = true }
        mtzBtn = Button(this).apply { text = "Export .mtz to Downloads (no Shizuku)"; setOnClickListener { start(Mode.MTZ) } }

        dirEdit = EditText(this).apply {
            setText("/sdcard/Android/data/com.android.thememanager/files/MIUI/theme/.data/content/icons")
        }
        themeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Grant Shizuku to load themes"))
        }
        refreshThemesBtn = Button(this).apply {
            text = "Refresh theme list"
            setOnClickListener { loadThemes() }
        }
        keepBox = CheckBox(this).apply { text = "Keep the theme's masks/borders (direct flash only)" }
        runBtn = Button(this).apply { text = "Build & flash into selected theme (Shizuku)"; setOnClickListener { start(Mode.FLASH) } }
        restoreBtn = Button(this).apply { text = "Restore backup (Shizuku)"; setOnClickListener { start(Mode.RESTORE) } }
        logView = TextView(this).apply { setTextIsSelectable(true); setPadding(0, dp(12), 0, 0) }

        root.addView(label("Icon pack")); root.addView(spinner); root.addView(pkgOverride)
        root.addView(label("Icon size px (blank = auto)")); root.addView(sizeEdit)
        root.addView(trimBox)
        root.addView(mtzBtn)
        root.addView(label("Direct Shizuku flash")); root.addView(status); root.addView(grant)
        root.addView(label("Target dummy theme (.mrc)")); root.addView(themeSpinner); root.addView(refreshThemesBtn)
        root.addView(label("Theme icons folder")); root.addView(dirEdit)
        root.addView(keepBox)
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
        val ready = Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        status.text = when {
            !Shizuku.pingBinder() -> "Shizuku: not running (open Shizuku app → Start via wireless debugging)"
            Shizuku.isPreV11() -> "Shizuku: unsupported version (update Shizuku)"
            ready -> "Shizuku: ready ✓"
            else -> "Shizuku: running — tap Grant Shizuku permission"
        }
        if (ready) loadThemes()
    }

    private fun loadThemes() {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            toast("Grant Shizuku permission first")
            return
        }
        val iconsDir = dirEdit.text.toString().trim().trimEnd('/')
        Thread {
            val found = try {
                Pipeline.listThemes(iconsDir)
            } catch (_: Throwable) {
                emptyList()
            }
            runOnUiThread {
                themes = found
                val labels = if (found.isEmpty()) listOf("No .mrc themes found") else found.map { it.second }
                themeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            }
        }.start()
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

    private enum class Mode { MTZ, FLASH, RESTORE }

    private fun start(mode: Mode) {
        if (mode != Mode.MTZ) {
            if (!Shizuku.pingBinder()) {
                toast("Start Shizuku first")
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(100)
                return
            }
        }
        val selectedPkg = pkgOverride.text.toString().trim().ifEmpty {
            packs.getOrNull(spinner.selectedItemPosition)?.first.orEmpty()
        }
        val iconsDir = dirEdit.text.toString().trim().trimEnd('/')
        val selectedMrc = themes.getOrNull(themeSpinner.selectedItemPosition)?.first
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
                when (mode) {
                    Mode.MTZ -> Pipeline.exportMtz(this, selectedPkg, trim, manualSize, appendLog)
                    Mode.FLASH -> Pipeline.run(this, selectedPkg, iconsDir, selectedMrc, trim, keepExtras, manualSize, appendLog)
                    Mode.RESTORE -> Pipeline.restore(this, iconsDir, selectedMrc, appendLog)
                }
            } catch (t: Throwable) {
                appendLog("ERROR: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                runOnUiThread { setBusy(false) }
            }
        }.start()
    }

    private fun setBusy(busy: Boolean) {
        mtzBtn.isEnabled = !busy
        runBtn.isEnabled = !busy
        restoreBtn.isEnabled = !busy
        refreshThemesBtn.isEnabled = !busy
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
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
    private lateinit var mtzBtn: Button
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
            text = "Grant Shizuku permission (only for direct flash)"
            setOnClickListener {
                if (!Shizuku.pingBinder()) toast("Start Shizuku first (Shizuku app → Start via wireless debugging)")
                else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) refreshStatus()
                else Shizuku.requestPermission(100)
            }
        }
        spinner = Spinner(this)
        pkgOverride = EditText(this).apply { hint = "…or type icon pack package name" }
        sizeEdit = EditText(this).apply {
            hint = "auto (266px for .mtz / theme size for flash)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        trimBox = CheckBox(this).apply { text = "Trim padding + resize icons"; isChecked = true }
        mtzBtn = Button(this).apply { text = "Export .mtz to Downloads (no Shizuku)"; setOnClickListener { start(Mode.MTZ) } }

        dirEdit = EditText(this).apply {
            setText("/sdcard/Android/data/com.android.thememanager/files/MIUI/theme/.data/content/icons")
        }
        keepBox = CheckBox(this).apply { text = "Keep the theme's masks/borders (direct flash only)" }
        runBtn = Button(this).apply { text = "Build & flash into dummy theme (Shizuku)"; setOnClickListener { start(Mode.FLASH) } }
        restoreBtn = Button(this).apply { text = "Restore backup (Shizuku)"; setOnClickListener { start(Mode.RESTORE) } }
        logView = TextView(this).apply { setTextIsSelectable(true); setPadding(0, dp(12), 0, 0) }

        root.addView(label("Icon pack")); root.addView(spinner); root.addView(pkgOverride)
        root.addView(label("Icon size px (blank = 266px for .mtz, or auto from theme)")); root.addView(sizeEdit)
        root.addView(trimBox)
        root.addView(mtzBtn)
        root.addView(label("Optional: Direct Shizuku flash")); root.addView(status); root.addView(grant)
        root.addView(label("Theme icons folder")); root.addView(dirEdit)
        root.addView(keepBox)
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
            !Shizuku.pingBinder() -> "Shizuku: not running (not needed for .mtz export)"
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

    private enum class Mode { MTZ, FLASH, RESTORE }

    private fun start(mode: Mode) {
        if (mode != Mode.MTZ) {
            if (!Shizuku.pingBinder()) {
                toast("Start Shizuku first")
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(100)
                return
            }
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
                when (mode) {
                    Mode.MTZ -> Pipeline.exportMtz(this, selectedPkg, trim, manualSize, appendLog)
                    Mode.FLASH -> Pipeline.run(this, selectedPkg, iconsDir, trim, keepExtras, manualSize, appendLog)
                    Mode.RESTORE -> Pipeline.restore(this, iconsDir, appendLog)
                }
            } catch (t: Throwable) {
                appendLog("ERROR: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                runOnUiThread { setBusy(false) }
            }
        }.start()
    }

    private fun setBusy(busy: Boolean) {
        mtzBtn.isEnabled = !busy
        runBtn.isEnabled = !busy
        restoreBtn.isEnabled = !busy
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
