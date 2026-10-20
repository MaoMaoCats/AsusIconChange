# AsusIconChange (ASUS & ROG Phone Icon Pack Applyer)

[![Android](https://img.shields.io/badge/Android-10%20to%2016%20(API%2036)-green.svg)](https://developer.android.com)
[![ROG Phone](https://img.shields.io/badge/Device-ASUS%20ROG%20Phone%209%20%2F%209%20FE%20%7C%20ZenUI-red.svg)](https://rog.asus.com)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Latest Release](https://img.shields.io/badge/Release-v1.6.0-orange.svg)](releases/ASUS_Icon_Pack_Applyer_1.6.0.apk)

> 💖 **Credits & Attribution**: This project is built upon and significantly expanded from [`asusiconpackapply`](https://github.com/Peketr/asusiconpackapply) originally created by **[Peketr (Peter Heup)](https://github.com/Peketr)**. Special thanks to Peketr for developing the foundational icon pack application mechanism for ASUS ZenUI!

---

## 🌟 Overview

**AsusIconChange** is an advanced icon pack manager and compatibility toolset engineered specifically for **ASUS ROG Phone (ROG Phone 9 / 9 FE, ROG Phone 8, ROG Phone 7)** and **ASUS ZenUI** devices running **Android 14, 15, and 16**.

Stock ASUS Launcher (`AsusLauncher.apk`) has unique internal restrictions that prevent standard third-party icon packs (from Icon Pack Studio, CandyBar, etc.) from applying properly, often causing:
1. **Resetting to Default Icons**: ASUS Launcher strictly requires a compiled binary Android XML at `res/xml/appfilter.xml` and shortened component names (`ComponentInfo{pkg/.Activity}`). Standard icon packs export plain text in `res/raw/` or `assets/`, causing ASUS Launcher to parse 0 icons and silently revert to default.
2. **Small / Shrunken Icons**: Exports in `drawable-nodpi` at 168×168 appear shrunken inside the device's native 192×192 `xxxhdpi` grid.
3. **Wallpaper Reset Bugs**: Older tools manipulated `WallpaperManager`, corrupting custom or live wallpapers.

**AsusIconChange** solves all of these problems with:
- 🛡️ **100% Wallpaper & Screen Layout Protection** (Zero wallpaper tampering).
- ⚡ **Full 192×192 Native Scaling** (Upscales icons with Lanczos resampling to match ROG Phone native density).
- 🎨 **Custom Shape Preservation** (Preserves exact curves, squircles, and geometry designed in Icon Pack Studio).
- 🛠️ **In-App 1-Tap Converter** (Automatically converts incompatible packs right inside the app!).
- 💻 **Termux / PRoot CLI Tooling** (Command-line helper scripts for power users).

---

## 🚀 Quick Start

### 1. Download & Install the APK
Download the latest APK from the [Releases tab](../../releases) or directly from this repository:
* 📥 [**ASUS_Icon_Pack_Applyer_1.6.0.apk**](releases/ASUS_Icon_Pack_Applyer_1.6.0.apk)

### 2. How to Use

#### A. Applying Already-Compatible Icon Packs:
1. Open **ASUS Icon Pack Applyer**.
2. Select any icon pack with the green badge **`✓ ASUS Launcher Compatible`**.
3. Tap **Apply to ASUS Launcher**.
4. ASUS Launcher instantly refreshes your home screen and app drawer icons.

#### B. Fixing Incompatible Packs (e.g. from Icon Pack Studio):
1. Create or download your icon pack in **Icon Pack Studio** or export any standard APK.
2. Open **ASUS Icon Pack Applyer**.
3. If the pack displays **`⚠️ Incompatible: Missing appfilter.xml`**, simply tap on it.
4. The action button turns orange: **`🛠️ Convert for ASUS Launcher`**.
5. Tap it: The app will upscale icons to 192×192, compile the binary `appfilter.xml`, and immediately prompt the Android system installer to install the converted pack!
6. Once installed, tap **Apply to ASUS Launcher**!

#### C. Ready-to-Install Banner:
Whenever an ASUS-converted icon pack is placed in your `/sdcard/Download/` folder, the app automatically shows an install card at the top:
> **📦 Converted Pack Ready**: Tap **Install** to install it directly without opening file managers.

---

## 🛡️ Safety & Wallpaper Protection

Unlike other tools that call `WallpaperManager.getInstance().setBitmap(...)` (which overwrites your home and lock screen wallpaper on Android 14+), **AsusIconChange** only interfaces with ASUS Launcher's internal icon cache broadcast:
```java
Intent("com.asus.launcher.intent.action.APPLY_ICONPACK")
```
* **Your home screen wallpaper is 100% untouched.**
* **Armoury Crate live wallpapers, static wallpapers, and widgets remain perfectly intact.**

---

## 💻 CLI & Termux / PRoot Tools

For advanced users or automated environments (such as Termux or PRoot Ubuntu on device), a complete CLI suite is included in `tools/`:

```bash
# 1. Start the in-app background conversion daemon:
./tools/apply_iconpack.sh daemon

# 2. Patch / fix any icon pack APK directly:
./tools/apply_iconpack.sh patch "/sdcard/Download/My_Icon_Pack.apk"

# 3. Patch an installed package by package name:
./tools/apply_iconpack.sh patch ginlemon.iconpackstudio.exported

# 4. Apply directly to ASUS Launcher via command line:
./tools/apply_iconpack.sh apply com.asus.iconpack.my_pack

# 5. Restore default ASUS / ROG system icons:
./tools/apply_iconpack.sh default

# 6. Check system and launcher status:
./tools/apply_iconpack.sh status
```

---

## 🔬 Technical Deep Dive: Why ASUS Launcher is Unique

Reverse-engineering `AsusLauncher.apk` on Android 16 revealed three critical architectural details:

### 1. Binary `res/xml/appfilter.xml` Lookup
In `com.asus.launcher.iconpack.IconPackUtils`:
```java
Resources resources = context.getPackageManager().getResourcesForApplication(str);
int identifier = resources.getIdentifier("appfilter", "xml", str);
```
`getIdentifier("appfilter", "xml", ...)` strictly searches compiled XML resources registered in `resources.arsc`. Standard icon packs store `appfilter.xml` in `assets/` or `res/raw/`, returning `0` and resulting in an empty mapping table.

### 2. ComponentName Flattening
In `com.asus.launcher.icons.LauncherIcons`:
```java
String key = "ComponentInfo{" + componentName.flattenToString() + "}";
```
`componentName.flattenToString()` contracts `com.example.app/com.example.app.MainActivity` into `com.example.app/.MainActivity` (with a dot prefix). If the icon pack only declares the unshortened class name, the lookup returns `null` and ASUS Launcher falls back to stock icons. **AsusIconChange** automatically synthesizes both full and flattened entries.

### 3. Density Scaling
Standard exports place icons in `res/drawable-nodpi/` at 168×168 px. On ROG Phone 9 displays (`xxxhdpi`, 440–560 dpi), ASUS Launcher expects 192×192 px. **AsusIconChange** crops transparent padding and resamples with high-quality Lanczos interpolation to fill 184×184 centered in 192×192 canvas.

---

## 📂 Project Structure

```
AsusIconChange/
├── app/                        # Android Studio App Project (ASUS Icon Pack Applyer)
│   ├── src/main/java/          # Kotlin UI & safety check logic
│   └── src/main/res/           # Layouts, themes, and FileProvider paths
├── tools/                      # CLI Tools & Background Services
│   ├── apply_iconpack.sh       # Bash management script
│   ├── fix_iconpack.py         # Python icon pack converter & 192x192 scaler
│   ├── converter_service.py    # Local HTTP conversion daemon (port 8899)
│   └── list3rd.jar             # Android IPC package resolver helper
├── releases/                   # Prebuilt production APKs
│   └── ASUS_Icon_Pack_Applyer_1.6.0.apk
└── README.md
```

---

## 📜 Credits & License

* **Original Author**: [Peketr (Peter Heup)](https://github.com/Peketr) — author of the initial `asusiconpackapply` project.
* **ROG Phone 9 / Android 16 Compatibility & Toolsuite**: Developed by MaoMaoCats.
* **License**: MIT License — open source and free for the Android community.
