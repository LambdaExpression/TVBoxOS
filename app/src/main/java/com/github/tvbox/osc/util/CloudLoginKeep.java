package com.github.tvbox.osc.util;

import android.os.Environment;

import com.github.tvbox.osc.base.App;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 爬虫包每次启动都会走 saveConfig，有的实现会用远程空模板盖掉已经登录的网盘。
 * 这里只在登录内容被清掉时，把启动前那一份写回去。不记录口令内容。
 */
public final class CloudLoginKeep {

    private static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_DEPTH = 3;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** 网盘登录字段。值太短或只是本机地址时不当成已登录。 */
    private static final String[] LOGIN_KEYS = {
            "Token", "aliToken", "quarkCookie", "ucCookie", "115Cookie", "ydAuth", "tyAuth", "refresh_token"
    };

    private CloudLoginKeep() {
    }

    /**
     * 记下当前还有效的网盘登录文件。没有登录内容时返回空表。
     */
    public static Map<File, byte[]> snapshot() {
        Map<File, byte[]> kept = new LinkedHashMap<File, byte[]>();
        try {
            App app = App.getInstance();
            if (app == null) {
                return kept;
            }
            collect(app.getFilesDir(), 0, kept);
            collect(app.getCacheDir(), 0, kept);
            collect(app.getExternalFilesDir(null), 0, kept);
            collect(app.getExternalCacheDir(), 0, kept);
            collect(new File(app.getApplicationInfo().dataDir, "shared_prefs"), 0, kept);
            File storage = Environment.getExternalStorageDirectory();
            if (storage != null) {
                collect(new File(storage, "TVBox"), 0, kept);
                collect(new File(storage, "tvbox"), 0, kept);
            }
        } catch (Throwable th) {
            LOG.e("echo-cloud-login-snapshot: " + th.getMessage());
        }
        return kept;
    }

    /**
     * saveConfig 之后，如果某个文件里的登录被清空或文件被删掉，写回启动前的内容。
     *
     * @param kept {@link #snapshot()} 的结果，允许为空
     */
    public static void restore(Map<File, byte[]> kept) {
        if (kept == null || kept.isEmpty()) {
            return;
        }
        for (Map.Entry<File, byte[]> entry : kept.entrySet()) {
            File file = entry.getKey();
            byte[] previous = entry.getValue();
            if (file == null || previous == null || previous.length == 0) {
                continue;
            }
            try {
                if (hasLogin(previous) && !hasLogin(read(file))) {
                    write(file, previous);
                    LOG.i("echo-cloud-login-restored: " + file.getName());
                }
            } catch (Throwable th) {
                LOG.e("echo-cloud-login-restore: " + file.getName() + " " + th.getMessage());
            }
        }
    }

    private static void collect(File dir, int depth, Map<File, byte[]> kept) {
        if (dir == null || depth > MAX_DEPTH || !dir.isDirectory()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child == null) {
                continue;
            }
            if (child.isDirectory()) {
                collect(child, depth + 1, kept);
                continue;
            }
            String name = child.getName();
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase();
            if (lower.endsWith(".jar") || lower.endsWith(".so") || lower.endsWith(".dex") || lower.endsWith(".apk")) {
                continue;
            }
            if (child.length() <= 0 || child.length() > MAX_BYTES) {
                continue;
            }
            byte[] data = read(child);
            if (hasLogin(data)) {
                kept.put(child, data);
            }
        }
    }

    private static boolean hasLogin(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        String text;
        try {
            text = new String(data, UTF8);
        } catch (Throwable th) {
            return false;
        }
        for (String key : LOGIN_KEYS) {
            String value = valueOf(text, key);
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.length() < 8) {
                continue;
            }
            if (trimmed.startsWith("http://127.0.0.1") || trimmed.startsWith("http://localhost")) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static String valueOf(String text, String key) {
        Matcher json = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(text);
        if (json.find()) {
            return json.group(1);
        }
        Matcher xml = Pattern.compile("name=\"" + Pattern.quote(key) + "\"[^>]*>([^<]*)<").matcher(text);
        if (xml.find()) {
            return xml.group(1);
        }
        return null;
    }

    private static byte[] read(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        FileInputStream input = null;
        try {
            if (file.length() <= 0 || file.length() > MAX_BYTES) {
                return null;
            }
            input = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset != data.length) {
                return null;
            }
            return data;
        } catch (Throwable th) {
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void write(File file, byte[] data) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("mkdir failed");
        }
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file);
            output.write(data);
            output.flush();
        } finally {
            if (output != null) {
                output.close();
            }
        }
    }
}
