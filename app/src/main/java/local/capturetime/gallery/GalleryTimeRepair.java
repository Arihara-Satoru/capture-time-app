package local.capturetime.gallery;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.media.ExifInterface;
import local.capturetime.time.CaptureTimeParser;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Root-only, offline repair of Xiaomi Gallery's stale time index. */
public final class GalleryTimeRepair {
    private static final String DB = "/data/user/0/com.miui.gallery/databases/gallery.db";
    private static final String[] FIELDS = {"_id", "localFile", "fileName", "dateTaken", "mixedDateTime",
        "dateModified", "exifDateTime", "localFlag", "serverStatus", "serverId", "sha1", "realSize", "realDateModified"};
    private static final CaptureTimeParser PARSER = CaptureTimeParser.INSTANCE;

    private static JSONObject row(Cursor c) throws Exception {
        JSONObject result = new JSONObject();
        for (String key : FIELDS) {
            int i = c.getColumnIndexOrThrow(key);
            result.put(key, c.isNull(i) ? JSONObject.NULL : c.getType(i) == Cursor.FIELD_TYPE_INTEGER ? c.getLong(i) : c.getString(i));
        }
        return result;
    }

    private static Long trustedTime(JSONObject r) throws Exception {
        String path = r.optString("localFile", "");
        File file = new File(path);
        if (!path.startsWith("/storage/emulated/0/") || !file.isFile()) return null;
        if (!file.getCanonicalPath().equals(path)) throw new IllegalStateException("Canonical path differs: " + file.getCanonicalPath());
        if (r.getLong("localFlag") != 0 && r.getLong("localFlag") != 7 && r.getLong("localFlag") != 8) return null;
        String name = file.getName();
        String recordedName = r.getString("fileName");
        if (!matchesFileName(recordedName, name, r.getLong("localFlag"))) return null;
        int dot = name.lastIndexOf('.');
        Instant named = PARSER.parseFilename(dot < 0 ? name : name.substring(0, dot));
        int recordedDot = recordedName.lastIndexOf('.');
        Instant recorded = PARSER.parseFilename(recordedDot < 0 ? recordedName : recordedName.substring(0, recordedDot));
        if (named == null || !named.equals(recorded)) return null;
        Instant original;
        try (java.io.FileInputStream stream = new java.io.FileInputStream(file)) {
            ExifInterface exif = new ExifInterface(stream);
            original = PARSER.parseExif(exif.getAttribute("DateTimeOriginal"), exif.getAttribute("OffsetTimeOriginal"));
        }
        // ponytail: require filename, original EXIF, and mtime to agree to the second;
        // other cases need an explicitly reviewed source instead of a guessed date.
        if (original == null || original.getEpochSecond() != named.getEpochSecond()
            || Math.floorDiv(file.lastModified(), 1000L) != original.getEpochSecond()) return null;
        return original.toEpochMilli();
    }

    static boolean matchesFileName(String recorded, String actual, long localFlag) {
        if (actual.equals(recorded)) return true;
        int dot = recorded.lastIndexOf('.');
        // ponytail: only Gallery's observed six-hex copy suffix is accepted for localFlag 7;
        // other renames need manual review before widening the rule.
        return localFlag == 7 && dot > 0 && actual.length() == recorded.length() + 7
            && actual.startsWith(recorded.substring(0, dot) + "_")
            && actual.endsWith(recorded.substring(dot))
            && actual.substring(dot + 1, dot + 7).matches("[0-9a-f]{6}");
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception | AssertionError error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        if (args.length == 0 || !java.util.Arrays.asList("plan", "apply", "verify", "self-check").contains(args[0]))
            throw new IllegalArgumentException("plan [id] | apply plan.json | verify plan.json | self-check");
        if (args[0].equals("self-check")) {
            Instant instant = PARSER.parseFilename("mmexport1552724154130");
            if (instant == null || instant.toEpochMilli() != 1552724154130L
                || !PARSER.formatDisplay(instant).equals("2019-03-16 16:15:54")) throw new AssertionError("filename parser");
            if (PARSER.parseFilename("backup_20221203_174311_1577440495004") != null) throw new AssertionError("ambiguous filename");
            System.out.println("SELF_CHECK_OK");
            return;
        }
        boolean apply = args[0].equals("apply");
        String database = args.length == 3 ? args[2] : DB;
        if (!database.equals(DB) && !database.startsWith("/data/local/tmp/xiaomi-gallery-"))
            throw new IllegalArgumentException("Test database must be in the dedicated temporary directory");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database, null,
                apply ? SQLiteDatabase.OPEN_READWRITE : SQLiteDatabase.OPEN_READONLY)) {
            if (args[0].equals("plan")) {
                JSONArray plan = new JSONArray();
                int inspected = 0;
                int cloudOnly = 0;
                if (args.length == 1) try (Cursor count = db.rawQuery(
                        "SELECT COUNT(*) FROM cloud WHERE serverType=1 AND localFile IS NULL", null)) {
                    if (count.moveToFirst()) cloudOnly = count.getInt(0);
                }
                String where = "serverType=1 AND localFile IS NOT NULL";
                String[] values = null;
                if (args.length == 2) { where += " AND _id=?"; values = new String[]{Long.toString(Long.parseLong(args[1]))}; }
                try (Cursor c = db.query("cloud", FIELDS, where, values, null, null, "_id")) {
                    while (c.moveToNext()) {
                        inspected++;
                        JSONObject r = row(c);
                        Long target;
                        try { target = trustedTime(r); } catch (Exception e) { if (args.length == 2) e.printStackTrace(); continue; }
                        if (target == null) continue;
                        if (Math.floorDiv(r.optLong("dateTaken"), 1000L) == target / 1000
                            && Math.floorDiv(r.optLong("mixedDateTime"), 1000L) == target / 1000
                            && Math.floorDiv(r.optLong("dateModified"), 1000L) == target / 1000) continue;
                        r.put("target", target);
                        r.put("targetExif", PARSER.formatExif(Instant.ofEpochMilli(target)));
                        plan.put(r);
                    }
                }
                System.err.println("Inspected=" + inspected + " CloudOnly=" + cloudOnly + " eligible=" + plan.length());
                System.out.println(plan.toString(2));
                return;
            }
            JSONArray plan = new JSONArray(new String(Files.readAllBytes(Paths.get(args[1])), java.nio.charset.StandardCharsets.UTF_8));
            Map<String, String> originalHashes = new LinkedHashMap<>();
            if (apply) for (int i = 0; i < plan.length(); i++) {
                JSONObject item = plan.getJSONObject(i);
                Long target = trustedTime(item);
                if (target == null || target.longValue() != item.getLong("target"))
                    throw new IllegalStateException("照片依据已变化，请重新检查");
                String path = item.getString("localFile");
                originalHashes.put(path, sha256(new File(path)));
            }
            if (apply) db.beginTransaction();
            try {
                for (int i = 0; i < plan.length(); i++) {
                    JSONObject old = plan.getJSONObject(i);
                    String[] id = {Long.toString(old.getLong("_id"))};
                    JSONObject current;
                    try (Cursor c = db.query("cloud", FIELDS, "_id=?", id, null, null, null)) {
                        if (!c.moveToFirst()) throw new IllegalStateException("Missing id " + id[0]);
                        current = row(c);
                    }
                    long target = old.getLong("target");
                    Long now = trustedTime(current);
                    if (now == null || now.longValue() != target) throw new IllegalStateException("File evidence changed: " + id[0]);
                    if (apply) {
                        for (String key : FIELDS) {
                            if (!String.valueOf(current.get(key)).equals(String.valueOf(old.get(key)))) throw new IllegalStateException("Changed " + key + ": " + id[0]);
                        }
                        ContentValues v = new ContentValues();
                        v.put("dateTaken", target);
                        v.put("mixedDateTime", target);
                        v.put("dateModified", target);
                        v.put("exifDateTime", PARSER.formatExif(Instant.ofEpochMilli(target)));
                        if (db.update("cloud", v, "_id=?", id) != 1) throw new IllegalStateException("Unexpected row count");
                    } else {
                        for (String key : new String[]{"dateTaken", "mixedDateTime", "dateModified"}) {
                            if (current.getLong(key) != target) throw new IllegalStateException("Verification failed " + key + ": " + id[0]);
                        }
                        if (!current.getString("exifDateTime").equals(PARSER.formatExif(Instant.ofEpochMilli(target))))
                            throw new IllegalStateException("Verification failed exifDateTime: " + id[0]);
                    }
                }
                if (apply) {
                    for (Map.Entry<String, String> entry : originalHashes.entrySet()) {
                        if (!entry.getValue().equals(sha256(new File(entry.getKey()))))
                            throw new IllegalStateException("照片在操作期间变化，整批回滚：" + entry.getKey());
                    }
                    db.setTransactionSuccessful();
                }
            } finally { if (apply) db.endTransaction(); }
            try (Cursor check = db.rawQuery("PRAGMA quick_check", null)) {
                if (!check.moveToFirst() || !check.getString(0).equals("ok")) throw new IllegalStateException("Database integrity check failed");
            }
            System.out.println((apply ? "APPLIED=" : "VERIFIED=") + plan.length());
        }
    }

    static String sha256(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[256 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
