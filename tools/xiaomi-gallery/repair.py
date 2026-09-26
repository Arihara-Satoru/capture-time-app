"""Repair Xiaomi Gallery's local time index through ADB and existing root access.

Default: produce a backed-up plan. --apply-plan applies that exact plan atomically.
Requires Python 3.10+, JDK 17, Android SDK 35, and this app installed on the phone.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import sqlite3
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
REMOTE_DB = "/data/user/0/com.miui.gallery/databases"
JAR = "/data/local/tmp/gallery-time-repair.jar"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--apply-plan", type=Path)
    group.add_argument("--verify-plan", type=Path)
    group.add_argument("--check", action="store_true", help="test transaction rollback in a disposable database")
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    adb_path = shutil.which("adb")
    if not adb_path:
        raise RuntimeError("adb must be on PATH")
    adb = [adb_path, "-s", args.serial]
    sdk = args.sdk or Path(adb_path).resolve().parent.parent
    output = ROOT / "local-backups" / time.strftime("xiaomi-gallery-%Y%m%d-%H%M%S")
    output.mkdir(parents=True, exist_ok=False)

    def run(command):
        return subprocess.run(command, check=True, capture_output=True, text=True, encoding="utf-8")

    def shell(command, root=False):
        return run(adb + ["shell", "su -c " + shlex.quote(command) if root else command]).stdout

    def push(path, remote):
        run(adb + ["push", str(path), remote])
        shell("chmod 600 " + shlex.quote(remote), root=True)

    def invoke(operation, database=None):
        command = "CLASSPATH=" + shlex.quote(JAR + ":" + apk) + " app_process /system/bin local.capturetime.gallery.GalleryTimeRepair " + operation
        if database:
            command += " " + shlex.quote(database)
        try:
            result = run(adb + ["shell", "su -c " + shlex.quote(command)])
        except subprocess.CalledProcessError as error:
            (output / "error.txt").write_text(error.stdout + "\n" + error.stderr, encoding="utf-8")
            raise
        (output / (operation.split()[0] + ".stderr.txt")).write_text(result.stderr, encoding="utf-8")
        return result.stdout

    def backup():
        remote = "/data/local/tmp/" + output.name + ".tar"
        digest = shell("cd " + REMOTE_DB + " && tar -cf " + remote + " gallery.db* gallery_lite_r.db* gallery_sub.db* && chmod 600 "
                       + remote + " && chown shell:shell " + remote + " && sha256sum " + remote, root=True).split()[0]
        local = output / "gallery-before.tar"
        run(adb + ["pull", remote, str(local)])
        if hashlib.sha256(local.read_bytes()).hexdigest() != digest:
            raise RuntimeError("Backup hash mismatch")
        (output / "backup.sha256").write_text(digest + "  gallery-before.tar\n", encoding="utf-8")
        print("Verified backup:", local, flush=True)

    def photo_hashes(rows):
        # ponytail: batches stay below shell argument limits; no new device utility is needed.
        return "".join(shell("sha256sum " + " ".join(shlex.quote(r["localFile"]) for r in rows[i:i + 100]))
                       for i in range(0, len(rows), 100))

    run([adb_path, "connect", args.serial])
    if "uid=0" not in shell("id", root=True):
        raise RuntimeError("Root is required")
    apk = shell("pm path local.capturetime").strip().removeprefix("package:")
    if not apk.startswith("/") or "\n" in apk:
        raise RuntimeError("Install local.capturetime first")
    android = sdk / "platforms/android-35/android.jar"
    classes = ROOT / "app/build/tmp/kotlin-classes/debug"
    compile_jar = ROOT / "app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar"
    if not (classes / "local/capturetime/time/CaptureTimeParser.class").exists() or not compile_jar.exists():
        raise RuntimeError("Run gradlew :app:testDebugUnitTest first")
    (output / "classes").mkdir()
    (output / "dex").mkdir()
    run(["javac", "-encoding", "UTF-8", "-source", "17", "-target", "17", "-cp", str(android) + os.pathsep + str(classes),
         "-d", str(output / "classes"), str(ROOT / "app/src/main/java/local/capturetime/gallery/GalleryTimeRepair.java")])
    d8 = sdk / "build-tools/35.0.0" / ("d8.bat" if os.name == "nt" else "d8")
    run([str(d8), "--min-api", "30", "--lib", str(android), "--classpath", str(compile_jar),
         "--output", str(output / "dex"), str(output / "classes/local/capturetime/gallery/GalleryTimeRepair.class")])
    jar = output / "gallery-time-repair.jar"
    run(["jar", "--create", "--file", str(jar), "-C", str(output / "dex"), "classes.dex"])
    push(jar, JAR)
    print(invoke("self-check").strip(), flush=True)

    if args.check:
        # First update succeeds; a stale second row must roll the whole transaction back.
        rows = json.loads(invoke("plan"))
        if not rows:
            raise RuntimeError("The rollback check needs one currently eligible row")
        row = rows[0]
        fields = [k for k in row if k not in ("target", "targetExif")]
        test_db = output / "test.db"
        with sqlite3.connect(test_db) as db:
            db.execute("CREATE TABLE cloud (" + ",".join('"' + k + '" ' + ("INTEGER" if isinstance(row[k], int) else "TEXT") for k in fields) + ")")
            db.execute("INSERT INTO cloud VALUES (" + ",".join("?" for _ in fields) + ")", [row[k] for k in fields])
        test_plan = output / "test-plan.json"
        test_plan.write_text(json.dumps([row, row]), encoding="utf-8")
        remote_db = "/data/local/tmp/" + output.name + "-test.db"
        remote_plan = "/data/local/tmp/" + output.name + "-test.json"
        push(test_db, remote_db)
        push(test_plan, remote_plan)
        try:
            invoke("apply " + remote_plan, remote_db)
        except subprocess.CalledProcessError as error:
            if "Changed dateTaken" not in error.stderr and "Changed mixedDateTime" not in error.stderr and "Changed dateModified" not in error.stderr:
                raise
        else:
            raise AssertionError("Stale plan was accepted")
        run(adb + ["pull", remote_db, str(output / "test-after.db")])
        with sqlite3.connect(output / "test-after.db") as db:
            actual = db.execute("SELECT * FROM cloud").fetchone()
            assert tuple(row[k] for k in fields) == actual, "Transaction did not roll back"
            assert db.execute("PRAGMA quick_check").fetchone()[0] == "ok"
        print("ROLLBACK_CHECK_OK", flush=True)
        return

    if args.verify_plan:
        push(args.verify_plan, "/data/local/tmp/capturetime-verify.json")
        print(invoke("verify /data/local/tmp/capturetime-verify.json"))
        return

    stopped = False
    try:
        shell("am force-stop com.miui.gallery")
        stopped = True
        backup()
        if args.apply_plan:
            rows = json.loads(args.apply_plan.read_text(encoding="utf-8"))
            if not rows:
                print("Empty plan; no changes")
                return
            hashes = photo_hashes(rows)
            (output / "photos-before.sha256").write_text(hashes, encoding="utf-8")
            shutil.copyfile(args.apply_plan, output / "applied-plan.json")
            push(args.apply_plan, "/data/local/tmp/capturetime-apply.json")
            owner = shell("stat -c '%u:%g' " + REMOTE_DB + "/gallery.db", root=True).strip()
            try:
                print(invoke("apply /data/local/tmp/capturetime-apply.json"), flush=True)
                print(invoke("verify /data/local/tmp/capturetime-apply.json"), flush=True)
            finally:
                shell("chown " + shlex.quote(owner) + " " + REMOTE_DB + "/gallery.db* && restorecon " + REMOTE_DB + "/gallery.db*", root=True)
            after = photo_hashes(rows)
            (output / "photos-after.sha256").write_text(after, encoding="utf-8")
            if after != hashes:
                raise RuntimeError("Photo file hashes changed; inspect the saved manifests")
            print("All photo hashes unchanged", flush=True)
        else:
            settings = ET.fromstring(shell("cat /data/user/0/local.capturetime/shared_prefs/settings.xml", root=True))
            values = {e.attrib["name"]: int(e.attrib["value"]) for e in settings if e.tag == "int"}
            tolerance = sum(values.get(k, 0) * scale for k, scale in (("days", 86400), ("hours", 3600), ("minutes", 60), ("seconds", 1))) * 1000
            rows = json.loads(invoke("plan"))
            rows = [r for r in rows if max(abs((r.get(k) or 0) - r["target"]) for k in ("dateTaken", "mixedDateTime", "dateModified")) > tolerance]
            (output / "plan.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
            print("Eligible:", len(rows), "Tolerance milliseconds:", tolerance, "Plan:", output / "plan.json", flush=True)
    finally:
        if stopped:
            shell("am start -n com.miui.gallery/.MainActivity")
    if args.apply_plan:
        print("After Gallery restart:", invoke("verify /data/local/tmp/capturetime-apply.json"), flush=True)
    print("Artifacts:", output, flush=True)


if __name__ == "__main__":
    main()
