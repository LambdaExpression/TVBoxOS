package com.github.tvbox.osc.util;

import android.util.Log;

import com.github.tvbox.osc.base.App;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用运行日志。除了打到系统日志，还在应用私有目录保留最近 500 条，供设置页查看。
 * 不接收播放器和第三方库直接调用 android.util.Log 的调试输出。
 */
public class LOG {
    private static final String TAG = "TVBox-runtime";
    private static final int MAX_LOG_LENGTH = 3000;
    /** 设置页里保留的条数。超出后丢掉最旧的。 */
    private static final int MAX_LINES = 500;
    /** 单条写入文件前的长度上限，避免一条超长响应占满 500 条里的空间。 */
    private static final int MAX_LINE_CHARS = 2000;
    private static final String FILE_NAME = "runtime.log";
    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    private static boolean loaded;

    /**
     * 读出上次留下的日志，并接住未捕获异常。应在应用启动、其它日志写出之前调用。
     */
    public static void init() {
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            loaded = true;
            loadFromDisk();
        }
        installCrashHandler();
    }

    public static void e(String msg) {
        Log.e(TAG, "" + msg);
        record("E", msg);
    }

    public static void i(String msg) {
        Log.i(TAG, "" + msg);
        record("I", msg);
    }

    public static void longI(String prefix, String msg) {
        longLog(Log.INFO, prefix, msg);
        record("I", (prefix == null ? "" : prefix) + (msg == null ? "null" : msg));
    }

    public static void longE(String prefix, String msg) {
        longLog(Log.ERROR, prefix, msg);
        record("E", (prefix == null ? "" : prefix) + (msg == null ? "null" : msg));
    }

    /**
     * 设置页展示用的全文。没有日志时返回空串。
     */
    public static String getText() {
        synchronized (LOCK) {
            if (!loaded) {
                loaded = true;
                loadFromDisk();
            }
            if (LINES.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (String line : LINES) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
            return builder.toString();
        }
    }

    /**
     * 清空内存和文件里的运行日志。
     */
    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
        }
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                writeSnapshot();
            }
        });
    }

    private static void record(String level, String msg) {
        String text = msg == null ? "null" : msg;
        if (text.length() > MAX_LINE_CHARS) {
            text = text.substring(0, MAX_LINE_CHARS) + "...";
        }
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(new Date())
                + " " + level + " " + text;
        synchronized (LOCK) {
            if (!loaded) {
                loaded = true;
                loadFromDisk();
            }
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                writeSnapshot();
            }
        });
    }

    /**
     * 崩溃时在当前线程把日志落盘，避免进程退出前后台写线程来不及执行。
     */
    private static void recordAndFlush(String level, String msg) {
        record(level, msg);
        writeSnapshot();
    }

    private static void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                StringWriter writer = new StringWriter();
                if (error != null) {
                    error.printStackTrace(new PrintWriter(writer));
                }
                String name = thread == null ? "" : thread.getName();
                recordAndFlush("E", "uncaught " + name + "\n" + writer);
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                }
            }
        });
    }

    private static void loadFromDisk() {
        File file = logFile();
        if (file == null || !file.isFile()) {
            return;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() == 0) {
                    continue;
                }
                LINES.addLast(line);
                while (LINES.size() > MAX_LINES) {
                    LINES.removeFirst();
                }
            }
        } catch (Throwable th) {
            Log.e(TAG, "echo-log-load: " + th.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void writeSnapshot() {
        String[] snapshot;
        synchronized (LOCK) {
            snapshot = LINES.toArray(new String[0]);
        }
        File file = logFile();
        if (file == null) {
            return;
        }
        FileOutputStream output = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.e(TAG, "echo-log-mkdir: " + parent.getAbsolutePath());
                return;
            }
            output = new FileOutputStream(file, false);
            for (int i = 0; i < snapshot.length; i++) {
                if (i > 0) {
                    output.write('\n');
                }
                output.write(snapshot[i].getBytes("UTF-8"));
            }
            output.flush();
        } catch (Throwable th) {
            Log.e(TAG, "echo-log-write: " + th.getMessage());
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static File logFile() {
        App app = App.getInstance();
        if (app == null) {
            return null;
        }
        return new File(app.getFilesDir(), FILE_NAME);
    }

    private static void longLog(int priority, String prefix, String msg) {
        String text = msg == null ? "null" : msg;
        String title = prefix == null ? "" : prefix;
        int length = text.length();
        if (length <= MAX_LOG_LENGTH) {
            Log.println(priority, TAG, title + text);
            return;
        }
        int count = (length + MAX_LOG_LENGTH - 1) / MAX_LOG_LENGTH;
        for (int i = 0; i < count; i++) {
            int start = i * MAX_LOG_LENGTH;
            int end = Math.min(start + MAX_LOG_LENGTH, length);
            Log.println(priority, TAG, title + "[" + (i + 1) + "/" + count + "] " + text.substring(start, end));
        }
    }
}
