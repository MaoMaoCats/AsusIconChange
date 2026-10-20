package com.huep.asusiconpackapplyer

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val tag = "ASUSIconPackApplyer"
    private var packItems = ArrayList<IconPackItem>()
    private var selectedApp: String = "system_default"
    private var selectedName: String = "Default Icons"
    private var selectedPosition: Int = 0
    private var adapter: IconPackListAdapter? = null

    private lateinit var listView: ListView
    private lateinit var loadingContainer: View
    private lateinit var cbPreserveWallpaper: CheckBox
    private lateinit var btnRestoreWallpaper: MaterialButton
    private lateinit var applyBtn: MaterialButton

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cachedWallpaperBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.listview)
        loadingContainer = findViewById(R.id.loading_container)
        cbPreserveWallpaper = findViewById(R.id.cb_preserve_wallpaper)
        btnRestoreWallpaper = findViewById(R.id.btn_restore_wallpaper)
        applyBtn = findViewById(R.id.apply_btn)

        // 1. Instantly display Default Icons so the user never sees a black screen
        val defaultLauncherIcon = try {
            packageManager.getApplicationIcon("com.asus.launcher")
        } catch (e: Exception) {
            ContextCompat.getDrawable(this, R.mipmap.ic_launcher)
        }

        val defaultItem = IconPackItem(
            packageName = "system_default",
            name = getString(R.string.default_icons),
            icon = defaultLauncherIcon,
            isDefault = true
        )
        packItems.add(defaultItem)
        selectedApp = "system_default"
        selectedName = defaultItem.name
        selectedPosition = 0

        adapter = IconPackListAdapter(this, packItems, selectedPosition)
        listView.adapter = adapter

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (position in 0 until packItems.size) {
                val item = packItems[position]
                if (!item.isPlaceholder) {
                    selectedPosition = position
                    selectedApp = item.packageName
                    selectedName = item.name
                    adapter?.setSelectedPosition(position)
                    Toast.makeText(this, "Selected: ${item.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        applyBtn.setOnClickListener {
            showSafetyApplyDialog()
        }

        btnRestoreWallpaper.setOnClickListener {
            restoreWallpaper()
        }

        // 2. Backup current wallpaper in background on startup for safety
        backgroundExecutor.execute {
            backupCurrentWallpaper()
        }

        // 3. Scan for custom icon packs in background
        startAsyncScan()
    }

    private fun getLabel(pack: ApplicationInfo): String {
        return try {
            pack.loadLabel(packageManager).toString()
        } catch (e: Exception) {
            pack.packageName
        }
    }

    private fun startAsyncScan() {
        loadingContainer.visibility = View.VISIBLE

        backgroundExecutor.execute {
            val detected = scanForIconPacks()

            mainHandler.post {
                loadingContainer.visibility = View.GONE

                // Preserve default at index 0
                val defaultItem = packItems.firstOrNull { it.isDefault }
                packItems.clear()
                if (defaultItem != null) {
                    packItems.add(defaultItem)
                }

                if (detected.isNotEmpty()) {
                    packItems.addAll(detected)
                } else {
                    packItems.add(
                        IconPackItem(
                            packageName = "",
                            name = getString(R.string.no_icon_packs),
                            icon = null,
                            isPlaceholder = true
                        )
                    )
                }

                var idx = packItems.indexOfFirst { it.packageName == selectedApp }
                if (idx < 0) idx = 0
                selectedPosition = idx
                adapter?.setSelectedPosition(idx)
                adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun scanForIconPacks(): List<IconPackItem> {
        val seenPackages = HashSet<String>()
        seenPackages.add(packageName)
        seenPackages.add("com.asus.launcher")
        seenPackages.add("system_default")

        val detectedPackages = LinkedHashSet<String>()

        // 1. Query standard icon pack intent filters (Fast query, < 20ms)
        val iconPackActions = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.gau.go.launcherex.theme",
            "com.dlto.atom.launcher.THEME",
            "com.fede.launcher.THEME_ICONPACK",
            "com.anddoes.launcher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "ginlemon.iconpackstudio.action.THEME",
            "com.novalauncher.IMAGE_PACK"
        )

        for (action in iconPackActions) {
            try {
                val intent = Intent(action)
                val resolveInfos: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.queryIntentActivities(intent, 0)
                }
                for (ri in resolveInfos) {
                    val pkg = ri.activityInfo?.packageName
                    if (!pkg.isNullOrEmpty() && !seenPackages.contains(pkg)) {
                        detectedPackages.add(pkg)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Intent query error", e)
            }
        }

        // 2. Targeted scan: only check packages containing "icon" or "theme" or Icon Pack Studio
        try {
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
            }

            for (appInfo in packages) {
                val pkg = appInfo.packageName
                if (seenPackages.contains(pkg) || detectedPackages.contains(pkg)) continue

                val lowerPkg = pkg.lowercase()
                val isCandidate = lowerPkg.contains("iconpack") ||
                        lowerPkg.contains("icon_pack") ||
                        lowerPkg.startsWith("ginlemon.iconpackstudio")

                if (isCandidate) {
                    detectedPackages.add(pkg)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Package list query error", e)
        }

        // 3. Build IconPackItems
        val result = ArrayList<IconPackItem>()
        for (pkg in detectedPackages) {
            try {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(pkg, 0)
                }
                val label = getLabel(appInfo)
                val icon = appInfo.loadIcon(packageManager)
                result.add(
                    IconPackItem(
                        packageName = pkg,
                        name = label,
                        icon = icon,
                        isDefault = false
                    )
                )
                seenPackages.add(pkg)
            } catch (e: Exception) {
                Log.w(tag, "Error loading pack $pkg", e)
            }
        }

        result.sortBy { it.name.lowercase() }
        return result
    }

    private fun backupCurrentWallpaper(): Boolean {
        return try {
            val wm = WallpaperManager.getInstance(applicationContext)
            val drawable = wm.drawable
            if (drawable != null) {
                val bmp = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                    drawable.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 1080
                    val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 2400
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
                cachedWallpaperBitmap = bmp

                // Save to file in cache directory
                val backupFile = File(cacheDir, "wallpaper_backup.png")
                FileOutputStream(backupFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
                }
                Log.d(tag, "Wallpaper backed up successfully to ${backupFile.absolutePath}")
                true
            } else {
                Log.w(tag, "WallpaperManager returned null drawable")
                false
            }
        } catch (e: Exception) {
            Log.e(tag, "Error backing up wallpaper", e)
            false
        }
    }

    private fun restoreWallpaper(): Boolean {
        return try {
            val wm = WallpaperManager.getInstance(applicationContext)
            val bmp = cachedWallpaperBitmap ?: run {
                val backupFile = File(cacheDir, "wallpaper_backup.png")
                if (backupFile.exists()) {
                    BitmapFactory.decodeFile(backupFile.absolutePath)
                } else {
                    null
                }
            }

            if (bmp != null) {
                wm.setBitmap(bmp)
                Toast.makeText(this, "Wallpaper restored!", Toast.LENGTH_SHORT).show()
                true
            } else {
                Toast.makeText(this, "No saved wallpaper backup found.", Toast.LENGTH_SHORT).show()
                false
            }
        } catch (e: Exception) {
            Log.e(tag, "Error restoring wallpaper", e)
            Toast.makeText(this, "Restore error: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun showSafetyApplyDialog() {
        if (selectedApp.isEmpty()) {
            Toast.makeText(this, "Please select an icon pack", Toast.LENGTH_SHORT).show()
            return
        }

        val preserve = cbPreserveWallpaper.isChecked

        val message = StringBuilder().apply {
            append("Apply \"$selectedName\" to ASUS Launcher?\n\n")
            append("Safety Checks:\n")
            if (preserve) {
                append("✓ Wallpaper Protection: Active (Wallpaper will be saved and protected)\n")
            } else {
                append("⚠ Wallpaper Protection: Disabled\n")
            }
            append("✓ Only app icons are updated (no global theme reset)\n")
            append("✓ Target: $selectedApp")
        }

        AlertDialog.Builder(this)
            .setTitle("Apply Icon Pack")
            .setMessage(message.toString())
            .setPositiveButton("Apply") { _, _ ->
                executeApply(selectedApp, selectedName, preserve)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeApply(pkg: String, name: String, preserveWallpaper: Boolean) {
        val targetPkg = if (pkg == "com.asus.launcher" || pkg == "system_default") {
            "system_default"
        } else {
            pkg
        }

        if (preserveWallpaper) {
            backupCurrentWallpaper()
        }

        var started = false
        val errors = ArrayList<String>()

        try {
            val asus = Intent("com.asus.launcher.intent.action.APPLY_ICONPACK").apply {
                setPackage("com.asus.launcher")
                component = ComponentName("com.asus.launcher", "com.asus.launcher.IconpackEmptyActivity")
                addCategory(Intent.CATEGORY_DEFAULT)
                putExtra("com.asus.launcher.iconpack.PACKAGE_NAME", targetPkg)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(asus)
            started = true
        } catch (e: Exception) {
            Log.w(tag, "Explicit intent failed", e)
            errors.add(e.message ?: "Failed explicit intent")
        }

        if (!started) {
            try {
                val asusFallback = Intent("com.asus.launcher.intent.action.APPLY_ICONPACK").apply {
                    setPackage("com.asus.launcher")
                    addCategory(Intent.CATEGORY_DEFAULT)
                    putExtra("com.asus.launcher.iconpack.PACKAGE_NAME", targetPkg)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(asusFallback)
                started = true
            } catch (e: Exception) {
                Log.w(tag, "Fallback intent failed", e)
                errors.add(e.message ?: "Failed fallback intent")
            }
        }

        if (started) {
            // If preserve wallpaper is enabled, restore the saved wallpaper after 1.8 seconds
            if (preserveWallpaper) {
                mainHandler.postDelayed({
                    try {
                        restoreWallpaper()
                    } catch (e: Exception) {
                        Log.e(tag, "Auto-restore wallpaper failed", e)
                    }
                }, 1800)
            }

            val msg = if (targetPkg == "system_default") {
                "Restoring Default ZenUI / ROG Icons..."
            } else {
                "Applying $name..."
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Failed to apply: ${errors.joinToString()}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.options, menu)
        val versionName = BuildConfig.VERSION_NAME
        menu?.add("v$versionName")?.apply {
            isEnabled = false
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.refresh_menu_item -> {
                startAsyncScan()
                Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.restore_wallpaper_menu_item -> {
                restoreWallpaper()
                true
            }
            R.id.playstore_menu_item -> {
                try {
                    val playStoreIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://search?q=icon%20pack")
                    )
                    startActivity(playStoreIntent)
                } catch (e: Exception) {
                    val webIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/search?q=icon%20pack&c=apps")
                    )
                    startActivity(webIntent)
                }
                true
            }
            R.id.github_menu_item -> {
                val browserIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/Peketr/asusiconpackapply")
                )
                startActivity(browserIntent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
    }
}
