package xyz.doikki.videoplayer.player;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 暂停时音频焦点被交还，电视上重新打开声卡会晚一拍。
 * 真正开播前先写一段静音，等播放头往前走，说明声音通道已经恢复。
 */
final class AudioResumeWarmup {
    private static final String TAG = "Tvbox-runtime";
    /** 声卡一直不开就不再等，避免暂停后按播放没有反应。 */
    private static final long TIMEOUT_MS = 1500L;
    private static final int SAMPLE_RATE = 44100;

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AudioResumeWarmup() {
    }

    /**
     * 在后台打开声卡。完成或超时后回到主线程。
     * 新的一次调用或 {@link #cancel()} 会让上一次的回调不再执行。
     *
     * @param onReady 声音通道已尝试恢复，不能为空
     */
    static void warm(final Runnable onReady) {
        if (onReady == null) {
            return;
        }
        final int token = SEQ.incrementAndGet();
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    playSilenceUntilHeard(token, onReady);
                } catch (RuntimeException ex) {
                    Log.e(TAG, "echo-audio-warmup: " + ex.getMessage());
                    postReady(token, onReady);
                }
            }
        }, "tvbox-audio-warmup");
        thread.start();
    }

    /**
     * 放弃还没结束的等待，对应回调不会再开播。
     */
    static void cancel() {
        SEQ.incrementAndGet();
    }

    /**
     * 播放头前进后先回调，静音轨先留着，等 {@link #cancel()} 再关。
     * 这样淡出的这几百毫秒里声卡不会重新睡下去。
     *
     * @param token   这一次等待的代号，变了就立刻停
     * @param onReady 主线程回调
     */
    private static void playSilenceUntilHeard(int token, Runnable onReady) {
        int bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (bufferSize <= 0) {
            Log.e(TAG, "echo-audio-warmup: buffer " + bufferSize);
            postReady(token, onReady);
            return;
        }
        AudioTrack track = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM);
        try {
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "echo-audio-warmup: not initialized");
                postReady(token, onReady);
                return;
            }
            track.play();
            byte[] silence = new byte[bufferSize];
            long start = SystemClock.uptimeMillis();
            boolean heard = false;
            while (token == SEQ.get() && SystemClock.uptimeMillis() - start < TIMEOUT_MS) {
                if (track.getPlaybackHeadPosition() > 0) {
                    heard = true;
                    break;
                }
                if (!writeSilence(track, silence)) {
                    postReady(token, onReady);
                    return;
                }
            }
            if (token != SEQ.get()) {
                return;
            }
            if (!heard) {
                Log.i(TAG, "echo-audio-warmup-timeout");
                postReady(token, onReady);
                return;
            }
            Log.i(TAG, "echo-audio-warmup-ready");
            postReady(token, onReady);
            while (token == SEQ.get()) {
                if (!writeSilence(track, silence)) {
                    return;
                }
            }
        } finally {
            releaseTrack(track);
        }
    }

    /**
     * 回到主线程。代号已经变了就不再通知。
     */
    private static void postReady(final int token, final Runnable onReady) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (token != SEQ.get() || onReady == null) {
                    return;
                }
                onReady.run();
            }
        });
    }

    /**
     * @return false 表示写入失败，调用方应结束这次等待
     */
    private static boolean writeSilence(AudioTrack track, byte[] silence) {
        int written = track.write(silence, 0, silence.length);
        if (written < 0) {
            Log.e(TAG, "echo-audio-warmup: write " + written);
            return false;
        }
        try {
            Thread.sleep(20);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    /**
     * 静音轨只用来探路，用完就关掉，避免和真正的播放抢声卡。
     */
    private static void releaseTrack(AudioTrack track) {
        if (track == null) {
            return;
        }
        try {
            track.pause();
            track.flush();
            track.release();
        } catch (RuntimeException ex) {
            Log.e(TAG, "echo-audio-warmup-release: " + ex.getMessage());
        }
    }
}
