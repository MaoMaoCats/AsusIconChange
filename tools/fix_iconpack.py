#!/usr/bin/env python3
"""
ASUS / ROG Phone Icon Pack Fixer & Scaler
Converts standard Android icon packs (e.g. from Icon Pack Studio or CandyBar)
into ASUS Launcher–compatible format by:
  1. Creating a compiled binary res/xml/appfilter.xml (which ASUS Launcher requires).
  2. Adding normalized ComponentInfo{pkg/.Class} mappings matching ComponentName.flattenToString().
  3. Rescaling icon drawables from 168x168 to 192x192 (full-size system icon scale) while preserving custom shape.
  4. Packaging and signing with Android debug keystore.
"""

import sys
import os
import io
import shutil
import zipfile
import subprocess
import xml.etree.ElementTree as ET
from PIL import Image

def process_icon_image(data, target_size=192, content_size=184):
    try:
        im = Image.open(io.BytesIO(data)).convert('RGBA')
        alpha = im.split()[-1]
        bbox = alpha.getbbox()
        if not bbox:
            return data
        cropped = im.crop(bbox)
        cw, ch = cropped.size
        scale = content_size / max(cw, ch)
        nw, nh = int(round(cw * scale)), int(round(ch * scale))
        resized = cropped.resize((nw, nh), Image.Resampling.LANCZOS)

        canvas = Image.new('RGBA', (target_size, target_size), (0, 0, 0, 0))
        offset = ((target_size - nw) // 2, (target_size - nh) // 2)
        canvas.paste(resized, offset, resized)

        buf = io.BytesIO()
        canvas.save(buf, format='PNG')
        return buf.getvalue()
    except Exception:
        return data

def resolve_apk_path(target):
    if os.path.exists(target):
        return target
    # If a package name was passed, query installed apk path
    if '.' in target:
        list3rd_jar = "/root/iconchange/list3rd.jar"
        if not os.path.exists(list3rd_jar):
            list3rd_jar = "/tmp/list3rd.jar"
        if os.path.exists(list3rd_jar):
            try:
                res = subprocess.check_output([
                    "/system/bin/app_process", f"-Djava.class.path={list3rd_jar}", "/", "List3rdParty"
                ], stderr=subprocess.DEVNULL).decode('utf-8')
                for line in res.splitlines():
                    if "->" in line:
                        pkg, path = line.split("->", 1)
                        if pkg.strip() == target.strip():
                            apk_path = path.strip()
                            if os.path.exists(apk_path):
                                print(f"[*] Found installed package '{target}' at:\n    {apk_path}")
                                return apk_path
            except Exception as e:
                print(f"[-] Could not resolve package path: {e}")
    return target

def parse_aapt_xmltree(apk_path, res_path='res/xml/appfilter.xml'):
    try:
        out = subprocess.check_output(['aapt', 'dump', 'xmltree', apk_path, res_path], stderr=subprocess.DEVNULL).decode('utf-8')
        root = ET.Element('resources')
        current_item = None
        for line in out.splitlines():
            line = line.strip()
            if line.startswith('E: item'):
                current_item = ET.SubElement(root, 'item')
            elif current_item is not None and line.startswith('A: '):
                parts = line[3:].split('=', 1)
                if len(parts) == 2:
                    attr_name = parts[0].strip()
                    val_part = parts[1].strip()
                    if 'Raw: "' in val_part:
                        val = val_part.split('Raw: "', 1)[1].rsplit('"', 1)[0]
                    elif '"' in val_part:
                        val = val_part.split('"', 1)[1].rsplit('"', 1)[0]
                    else:
                        val = val_part
                    current_item.set(attr_name, val)
        return root
    except Exception as e:
        print(f"[-] aapt xmltree dump failed: {e}")
        return None

def fix_icon_pack(apk_path, output_apk_path=None):
    apk_path = resolve_apk_path(apk_path)
    if not os.path.exists(apk_path):
        print(f"Error: APK not found at {apk_path}")
        sys.exit(1)

    apk_name = os.path.splitext(os.path.basename(apk_path))[0]
    if apk_name.lower() == "base":
        apk_name = "Exported_Icon_Pack_New"
    if not output_apk_path:
        output_apk_path = f"/sdcard/Download/{apk_name}_ASUS_Fixed.apk"

    print(f"[*] Processing: {apk_path}")
    work_dir = "/tmp/iconpack_fix_work"
    if os.path.exists(work_dir):
        shutil.rmtree(work_dir)
    os.makedirs(f"{work_dir}/res/xml", exist_ok=True)
    os.makedirs(f"{work_dir}/res/drawable-nodpi", exist_ok=True)
    os.makedirs(f"{work_dir}/compiled", exist_ok=True)

    root = None
    with zipfile.ZipFile(apk_path, 'r') as z:
        # 1. Look for plain text appfilter first
        for candidate in ['assets/appfilter.xml', 'res/raw/appfilter.xml']:
            if candidate in z.namelist():
                try:
                    data = z.read(candidate)
                    root = ET.fromstring(data)
                    print(f"[*] Found plain text appfilter at: {candidate}")
                    break
                except Exception:
                    pass

        # 2. Fallback to binary res/xml/appfilter.xml
        if root is None and 'res/xml/appfilter.xml' in z.namelist():
            print("[*] Found binary res/xml/appfilter.xml, parsing with aapt...")
            root = parse_aapt_xmltree(apk_path, 'res/xml/appfilter.xml')

        if root is None:
            print("[-] Error: Could not locate or parse appfilter.xml.")
            sys.exit(1)

        new_root = ET.Element('resources')
        count = 0
        drawables_to_keep = set()

        for item in root.findall('item'):
            comp = item.get('component', '')
            draw = item.get('drawable', '')
            if not comp or not draw:
                continue

            drawables_to_keep.add(draw)

            # Original entry
            el1 = ET.SubElement(new_root, 'item')
            el1.set('component', comp)
            el1.set('drawable', draw)
            count += 1

            # Flattened ComponentName entry for ASUS Launcher
            if comp.startswith('ComponentInfo{') and comp.endswith('}'):
                inner = comp[14:-1]
                if '/' in inner:
                    pkg, cls = inner.split('/', 1)
                    if cls.startswith(pkg + '.') and len(cls) > len(pkg) + 1:
                        short_cls = cls[len(pkg):]  # Starts with '.'
                        short_comp = f'ComponentInfo{{{pkg}/{short_cls}}}'
                        el2 = ET.SubElement(new_root, 'item')
                        el2.set('component', short_comp)
                        el2.set('drawable', draw)
                        count += 1

        # Write res/xml/appfilter.xml
        tree = ET.ElementTree(new_root)
        tree.write(f"{work_dir}/res/xml/appfilter.xml", encoding='utf-8', xml_declaration=True)
        print(f"[+] Created res/xml/appfilter.xml with {count} component mappings.")

        # Extract and upscale drawables to 192x192
        extracted = 0
        print("[*] Rescaling icon drawables to full 192x192 size...")
        for name in z.namelist():
            if name.startswith('res/drawable') and name.endswith('.png'):
                base = os.path.basename(name)
                # Skip 9-patches and android framework leftovers
                if base.endswith('.9.png') or base.startswith(('abc_', 'btn_', 'notification_')):
                    continue
                raw_data = z.read(name)
                scaled_data = process_icon_image(raw_data, target_size=192, content_size=184)
                with open(os.path.join(work_dir, 'res/drawable-nodpi', base), 'wb') as f:
                    f.write(scaled_data)
                extracted += 1
        print(f"[+] Extracted & upscaled {extracted} icon drawables.")

        # Ensure ic_launcher exists
        launcher_icon_dest = os.path.join(work_dir, 'res/drawable-nodpi/ic_launcher.png')
        if not os.path.exists(launcher_icon_dest):
            candidates = [f for f in os.listdir(os.path.join(work_dir, 'res/drawable-nodpi')) if f.endswith('.png')]
            if candidates:
                shutil.copyfile(os.path.join(work_dir, 'res/drawable-nodpi', candidates[0]), launcher_icon_dest)

    # Extract human-readable label from original APK
    app_label = None
    try:
        out = subprocess.check_output(["aapt", "dump", "badging", apk_path], stderr=subprocess.DEVNULL).decode('utf-8')
        for line in out.splitlines():
            if line.startswith("application-label:"):
                app_label = line.split(":", 1)[1].strip().strip("'")
                break
    except Exception:
        pass
    if not app_label or app_label.lower() == "base":
        app_label = apk_name if apk_name.lower() != "base" else "Exported Icon Pack"

    safe_pkg_suffix = ''.join(c if c.isalnum() else '_' for c in app_label.lower()).strip('_')[:20]
    if not safe_pkg_suffix:
        safe_pkg_suffix = "custom"
    pkg_name = f"com.asus.iconpack.{safe_pkg_suffix}"
    label = f"{app_label} (ASUS)"

    if not output_apk_path:
        output_apk_path = f"/sdcard/Download/{safe_pkg_suffix}_ASUS_Fixed.apk"

    manifest = f'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{pkg_name}"
    android:versionCode="1"
    android:versionName="1.0">
    <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="34" />
    <application
        android:allowBackup="true"
        android:icon="@drawable/ic_launcher"
        android:label="{label}"
        android:hasCode="false">
        <activity
            android:name=".DummyActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="com.anddoes.launcher.THEME" />
                <category android:name="com.teslacoilsw.launcher.THEME" />
            </intent-filter>
            <intent-filter>
                <action android:name="org.adw.launcher.THEMES" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
            <intent-filter>
                <action android:name="com.novalauncher.THEME" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
    </application>
</manifest>'''

    with open(f"{work_dir}/AndroidManifest.xml", "w") as f:
        f.write(manifest)

    aapt2 = shutil.which("aapt2") or "/usr/bin/aapt2"
    android_jar = "/root/Android/Sdk/platforms/android-33/android.jar"
    if not os.path.exists(android_jar):
        android_jar = "/root/Android/Sdk/platforms/android-36/android.jar"

    keystore = "/root/.android/debug.keystore"
    if not os.path.exists(keystore):
        keystore = "/tmp/debug.keystore"
    if not os.path.exists(keystore):
        os.makedirs(os.path.dirname(keystore), exist_ok=True)
        subprocess.run([
            "keytool", "-genkey", "-v",
            "-keystore", keystore,
            "-storepass", "android",
            "-alias", "androiddebugkey",
            "-keypass", "android",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-dname", "CN=Android Debug,O=Android,C=US"
        ], check=True)

    print("[*] Compiling resources with aapt2...")
    subprocess.run([aapt2, "compile", "--dir", f"{work_dir}/res", "-o", f"{work_dir}/compiled/"], check=True)

    print("[*] Linking APK...")
    flat_files = [os.path.join(f"{work_dir}/compiled", f) for f in os.listdir(f"{work_dir}/compiled") if f.endswith(".flat")]
    subprocess.run([
        aapt2, "link",
        "-I", android_jar,
        "--manifest", f"{work_dir}/AndroidManifest.xml",
        "-o", f"{work_dir}/unaligned.apk"
    ] + flat_files, check=True)

    print("[*] Aligning and signing APK...")
    subprocess.run(["zipalign", "-f", "-v", "-p", "4", f"{work_dir}/unaligned.apk", f"{work_dir}/aligned.apk"], check=True, stdout=subprocess.DEVNULL)
    subprocess.run([
        "apksigner", "sign",
        "--ks", keystore,
        "--ks-pass", "pass:android",
        "--ks-key-alias", "androiddebugkey",
        f"{work_dir}/aligned.apk"
    ], check=True)

    shutil.copyfile(f"{work_dir}/aligned.apk", output_apk_path)
    print(f"[SUCCESS] ASUS-compatible APK ready at:\n  --> {output_apk_path}")
    print(f"Package: {pkg_name}")

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <path_to_icon_pack.apk or package_name> [output_path.apk]")
        sys.exit(1)
    fix_icon_pack(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
