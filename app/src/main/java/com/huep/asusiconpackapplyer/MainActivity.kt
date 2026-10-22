package com.huep.asusiconpackapplyer

import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
    private lateinit var applyBtn: MaterialButton
    private lateinit var cardReadyApk: MaterialCardView
    private lateinit var tvReadyApkTitle: TextView
    private lateinit var tvReadyApkDesc: TextView
    private lateinit var btnInstallReadyApk: MaterialButton

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.listview)
        loadingContainer = findViewById(R.id.loading_container)
        applyBtn = findViewById(R.id.apply_btn)
        cardReadyApk = findViewById(R.id.card_ready_apk)
        tvReadyApkTitle = findViewById(R.id.tv_ready_apk_title)
        tvReadyApkDesc = findViewById(R.id.tv_ready_apk_desc)
        btnInstallReadyApk = findViewById(R.id.btn_install_ready_apk)

        // 1. Instantly display Default Icons
        val defaultLauncherIcon = try {
            packageManager.getApplicationIcon("com.asus.launcher")
        } catch (e: Exception) {
            ContextCompat.getDrawable(this, R.mipmap.ic_launcher)
        }

        val defaultItem = IconPackItem(
            packageName = "system_default",
            name = getString(R.string.default_icons),
            icon = defaultLauncherIcon,
            isDefault = true,
            isAsusCompatible = true,
            compatibilityNote = "Stock ASUS ZenUI / ROG Icons"
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
                    updateActionButtonState(item)
                    Toast.makeText(this, "Selected: ${item.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        applyBtn.setOnClickListener {
            handleActionClick()
        }

        // 2. Scan for custom icon packs in background
        startAsyncScan()
    }

    override fun onResume() {
        super.onResume()
        checkReadyFixedApks()
        startAsyncScan()
    }

    private fun updateActionButtonState(item: IconPackItem) {
        if (item.isDefault || item.isAsusCompatible) {
            applyBtn.text = getString(R.string.apply_button)
            applyBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1976D2")) // Primary blue
            applyBtn.setIconResource(R.drawable.add_to_home)
        } else {
            applyBtn.text = "🛠️ Convert for ASUS Launcher"
            applyBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E65100")) // Orange
            applyBtn.setIconResource(android.R.drawable.ic_menu_manage)
        }
    }

    private fun checkReadyFixedApks() {
        backgroundExecutor.execute {
            val downloadDir = File(Environment.getExternalStorageDirectory(), "Download")
            var readyFile: File? = null

            // Prioritize the newest fixed pack
            val newestFixed = File(downloadDir, "Exported_Icon_Pack_New_ASUS_Fixed.apk")
            if (newestFixed.exists() && !isPackageInstalled("com.asus.iconpack.exported_icon_pack")) {
                readyFile = newestFixed
            } else {
                val fixedApks = downloadDir.listFiles { f ->
                    f.isFile && f.name.endsWith("_ASUS_Fixed.apk")
                }
                readyFile = fixedApks?.firstOrNull()
            }

            val finalFile = readyFile
            mainHandler.post {
                if (finalFile != null && finalFile.exists()) {
                    cardReadyApk.visibility = View.VISIBLE
                    tvReadyApkTitle.text = "📦 Converted Pack Ready: ${finalFile.name}"
                    tvReadyApkDesc.text = "Scaled to full 192x192 with ASUS appfilter. Tap to install!"
                    btnInstallReadyApk.setOnClickListener {
                        promptInstallApk(finalFile)
                    }
                } else {
                    cardReadyApk.visibility = View.GONE
                }
            }
        }
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun promptInstallApk(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, "APK file not found: ${file.absolutePath}", Toast.LENGTH_SHORT).show()
            return
        }

        // On Android 8.0+ (Oreo to Android 16), check REQUEST_INSTALL_PACKAGES permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(this)
                    .setTitle("Permission Needed: Install Unknown Apps")
                    .setMessage(
                        "Android requires permission to install converted icon packs.\n\n" +
                        "1. Tap 'Grant Permission' below.\n" +
                        "2. Turn ON 'Allow from this source'.\n" +
                        "3. Return here and tap Install again.\n\n" +
                        "(You can also install manually by opening your phone's File Manager / Download folder and tapping \"${file.name}\")."
                    )
                    .setPositiveButton("Grant Permission") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                            } catch (e2: Exception) {
                                Toast.makeText(this, "Please allow 'Install unknown apps' in Android Settings -> Apps", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
        }

        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                applicationContext,
                "${packageName}.provider",
                file
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
            Toast.makeText(this, "Opening installer for ${file.name}...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(tag, "Install prompt failed", e)
            AlertDialog.Builder(this)
                .setTitle("Installer Notice")
                .setMessage(
                    "Could not launch package installer: ${e.message}\n\n" +
                    "The converted APK is saved in your Download folder:\n" +
                    "${file.absolutePath}\n\n" +
                    "You can open your phone's Files / File Manager app, go to Downloads, and tap \"${file.name}\" to install it directly."
                )
                .setPositiveButton("OK", null)
                .show()
        }
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

                if (selectedPosition in 0 until packItems.size) {
                    updateActionButtonState(packItems[selectedPosition])
                }
            }
        }
    }

    /**
     * Safety Check: Verifies if an icon pack APK has compiled res/xml/appfilter.xml.
     */
    private fun checkAsusCompatibility(pkg: String): Boolean {
        if (pkg == "system_default" || pkg == "com.asus.launcher") return true
        return try {
            val res = packageManager.getResourcesForApplication(pkg)
            val resId = res.getIdentifier("appfilter", "xml", pkg)
            resId != 0
        } catch (e: Exception) {
            false
        }
    }

    private fun scanForIconPacks(): List<IconPackItem> {
        val seenPackages = HashSet<String>()
        seenPackages.add(packageName)
        seenPackages.add("com.asus.launcher")
        seenPackages.add("system_default")

        val detectedPackages = LinkedHashSet<String>()

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

        try {
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
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
                val isCompatible = checkAsusCompatibility(pkg)

                result.add(
                    IconPackItem(
                        packageName = pkg,
                        name = label,
                        icon = icon,
                        isDefault = false,
                        isAsusCompatible = isCompatible,
                        compatibilityNote = if (isCompatible) "ASUS Ready" else "Needs ASUS Patch"
                    )
                )
                seenPackages.add(pkg)
            } catch (e: Exception) {
                Log.w(tag, "Error loading pack $pkg", e)
            }
        }

        // ASUS-compatible packs on top, then alphabetical
        result.sortWith(compareByDescending<IconPackItem> { it.isAsusCompatible }.thenBy { it.name.lowercase() })
        return result
    }

    private fun handleActionClick() {
        if (selectedPosition !in 0 until packItems.size) {
            Toast.makeText(this, "Please select an icon pack", Toast.LENGTH_SHORT).show()
            return
        }

        val item = packItems[selectedPosition]
        if (item.isPlaceholder) return

        if (item.isDefault) {
            executeApply("system_default", item.name)
            return
        }

        if (!item.isAsusCompatible) {
            // Incompatible! Directly trigger converter
            convertIconPack(item)
        } else {
            // Fully compatible! Apply cleanly.
            AlertDialog.Builder(this)
                .setTitle("Apply Icon Pack")
                .setMessage(
                    "Apply \"${item.name}\" to ASUS Launcher?\n\n" +
                    "✓ Status: Verified ASUS Launcher Compatible\n" +
                    "✓ Wallpaper Safety: Your wallpaper is 100% untouched\n" +
                    "✓ App Drawer & Home Icons will be updated."
                )
                .setPositiveButton("Apply") { _, _ ->
                    executeApply(item.packageName, item.name)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    @Suppress("DEPRECATION")
    private fun convertIconPack(item: IconPackItem) {
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Converting Icon Pack")
            setMessage("Converting \"${item.name}\" for ASUS Launcher...\n\n• Upscaling to full 192x192 size\n• Preserving custom shape\n• Generating binary res/xml/appfilter.xml\n• Signing package")
            setCancelable(false)
            show()
        }

        backgroundExecutor.execute {
            var success = false
            var outputApkPath: String? = null
            var errorMessage: String? = null

            try {
                val encodedPkg = URLEncoder.encode(item.packageName, "UTF-8")
                val url = URL("http://127.0.0.1:8899/convert?package=$encodedPkg")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 60000
                conn.doInput = true

                val responseCode = conn.responseCode
                val responseStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val responseText = responseStream.bufferedReader().use { it.readText() }

                val json = JSONObject(responseText)
                if (json.optBoolean("success", false)) {
                    success = true
                    outputApkPath = json.optString("output_apk")
                } else {
                    errorMessage = json.optString("error", "Unknown conversion error")
                }
            } catch (e: Exception) {
                Log.e(tag, "Conversion request failed", e)
                errorMessage = e.message
            }

            mainHandler.post {
                progressDialog.dismiss()
                if (success && !outputApkPath.isNullOrEmpty()) {
                    val apkFile = File(outputApkPath)
                    AlertDialog.Builder(this)
                        .setTitle("✓ Conversion Complete!")
                        .setMessage(
                            "\"${item.name}\" was converted successfully!\n\n" +
                            "• Full 192x192 Size: Applied\n" +
                            "• Custom Shape: Preserved\n" +
                            "• ASUS Appfilter: Compiled\n\n" +
                            "Tap 'Install' to update the pack on your system."
                        )
                        .setPositiveButton("Install Now") { _, _ ->
                            promptInstallApk(apkFile)
                        }
                        .setNegativeButton("Later", null)
                        .show()
                } else {
                    // Daemon not running or failed
                    AlertDialog.Builder(this)
                        .setTitle("Converter Service Notice")
                        .setMessage(
                            "Automatic in-app conversion requires the local converter daemon:\n" +
                            (errorMessage ?: "Connection refused on localhost:8899") + "\n\n" +
                            "To start the daemon in Termux / PRoot:\n" +
                            "  /root/iconchange/apply_iconpack.sh daemon\n\n" +
                            "Or convert manually:\n" +
                            "  /root/iconchange/apply_iconpack.sh patch \"${item.packageName}\""
                        )
                        .setPositiveButton("Copy Command") { _, _ ->
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Fix command", "/root/iconchange/apply_iconpack.sh patch \"${item.packageName}\"")
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this, "Command copied to clipboard!", Toast.LENGTH_SHORT).show()
                        }
                        .setNeutralButton("Apply Anyway") { _, _ ->
                            executeApply(item.packageName, item.name)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun executeApply(pkg: String, name: String) {
        val targetPkg = if (pkg == "com.asus.launcher" || pkg == "system_default") {
            "system_default"
        } else {
            pkg
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
            val msg = if (targetPkg == "system_default") {
                "Restoring Default ZenUI / ROG Icons..."
            } else {
                "Applied $name! ASUS Launcher is refreshing icons..."
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
                checkReadyFixedApks()
                Toast.makeText(this, "Scanning...", Toast.LENGTH_SHORT).show()
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
