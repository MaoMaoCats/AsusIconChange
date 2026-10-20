#!/bin/bash
# ASUS / ROG Phone Icon Pack Manager (PRoot / Termux CLI Helper)

set -e

export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-arm64"

AM_CMD="/data/data/com.termux/files/usr/bin/am"
APK_PATH="/root/iconchange/asusiconpackapply/app/build/outputs/apk/debug/app-debug.apk"
SDCARD_APK="/sdcard/Download/ASUS_Icon_Pack_Applyer_1.6.0.apk"
FIXER_SCRIPT="/root/iconchange/fix_iconpack.py"

show_help() {
    echo "=========================================================="
    echo " ASUS / ROG Phone Icon Pack Manager (ROG Phone 9 / 9 FE)"
    echo "=========================================================="
    echo "Usage:"
    echo "  $0 daemon                  - Start background converter service"
    echo "  $0 patch <icon_pack.apk>   - Fix any icon pack for ASUS Launcher"
    echo "  $0 apply <package_name>    - Apply icon pack to ASUS Launcher"
    echo "  $0 default                 - Restore ASUS / ROG default system icons"
    echo "  $0 build                   - Rebuild Android app APK"
    echo "  $0 copy                    - Copy APK to /sdcard/Download for easy install"
    echo "  $0 status                  - Check ASUS Launcher & app status"
    echo "=========================================================="
}

cmd_daemon() {
    if pgrep -f "converter_service.py" >/dev/null; then
        echo "==> Converter daemon is already running on http://127.0.0.1:8899"
    else
        echo "==> Starting converter daemon on http://127.0.0.1:8899..."
        nohup /usr/bin/python3 /root/iconchange/converter_service.py >/tmp/converter.log 2>&1 &
        sleep 1
        echo "==> Daemon started! PID: $(pgrep -f 'converter_service.py' || echo 'unknown')"
    fi
}

cmd_patch() {
    local input_apk="$1"
    local output_apk="$2"
    if [ -z "$input_apk" ]; then
        echo "Error: input icon pack APK path required."
        echo "Usage:"
        echo "  $0 patch \"/sdcard/Download/my_pack.apk\""
        exit 1
    fi
    python3 "$FIXER_SCRIPT" "$input_apk" "$output_apk"
}

cmd_apply() {
    local pkg="$1"
    if [ -z "$pkg" ]; then
        echo "Error: package name required."
        echo "Example:"
        echo "  $0 apply default"
        echo "  $0 apply com.asus.iconpack.exportediconpack07_5"
        exit 1
    fi

    if [ "$pkg" = "default" ] || [ "$pkg" = "com.asus.launcher" ]; then
        pkg="system_default"
    fi

    echo "==> Sending icon pack intent to ASUS Launcher..."
    echo "    Target: $pkg"
    echo "    Wallpaper status: Untouched & Safe"

    if [ -x "$AM_CMD" ]; then
        # 1. Start ASUS Launcher's IconpackEmptyActivity
        "$AM_CMD" start --user 0 \
            -a com.asus.launcher.intent.action.APPLY_ICONPACK \
            -c android.intent.category.DEFAULT \
            -n com.asus.launcher/com.asus.launcher.IconpackEmptyActivity \
            --es "com.asus.launcher.iconpack.PACKAGE_NAME" "$pkg"

        # 2. Broadcast fallback for theme receivers
        "$AM_CMD" broadcast --user 0 \
            -a com.asus.launcher.intent.action.APPLY_ICONPACK \
            --es "com.asus.launcher.iconpack.PACKAGE_NAME" "$pkg" >/dev/null 2>&1 || true

        echo "==> Done! Icon pack update triggered."
    else
        echo "Error: $AM_CMD not found."
        exit 1
    fi
}

cmd_default() {
    cmd_apply "system_default"
}

cmd_build() {
    echo "==> Building ASUS Icon Pack Applyer APK..."
    cd /root/iconchange/asusiconpackapply
    ./gradlew --no-daemon assembleDebug
    echo "==> Build successful!"
    cmd_copy
}

cmd_copy() {
    if [ -f "$APK_PATH" ]; then
        cp "$APK_PATH" "$SDCARD_APK"
        echo "==> APK copied to: $SDCARD_APK"
        echo "    You can tap and install it from your Download folder via Files/ZArchiver."
    else
        echo "Error: APK not found at $APK_PATH. Run '$0 build' first."
        exit 1
    fi
}

cmd_status() {
    echo "==> Checking ASUS Launcher & System..."
    if [ -f "/system/priv-app/AsusLauncher/AsusLauncher.apk" ]; then
        echo "  [OK] AsusLauncher.apk found in /system/priv-app/AsusLauncher"
    else
        echo "  [WARN] AsusLauncher.apk not found in default path"
    fi

    if [ -f "$SDCARD_APK" ]; then
        echo "  [OK] Built APK ready at: $SDCARD_APK"
    else
        echo "  [INFO] Built APK not yet in /sdcard/Download"
    fi

    echo "  [INFO] Current device model: $(getprop ro.product.model 2>/dev/null || echo 'Unknown')"
    echo "  [INFO] Android release: $(getprop ro.build.version.release 2>/dev/null || echo 'Unknown')"
}

case "$1" in
    daemon)
        cmd_daemon
        ;;
    patch)
        cmd_patch "$2" "$3"
        ;;
    default)
        cmd_default
        ;;
    apply)
        cmd_apply "$2"
        ;;
    build)
        cmd_build
        ;;
    copy)
        cmd_copy
        ;;
    status)
        cmd_status
        ;;
    *)
        show_help
        ;;
esac
