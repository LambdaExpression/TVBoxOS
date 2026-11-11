package xyz.doikki.videoplayer.exo;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.database.ExoDatabaseProvider;
import com.google.android.exoplayer2.ext.rtmp.RtmpDataSourceFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.rtsp.RtspMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheDataSource;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;

/**
 * 按地址创建 Exo 媒体源。点播缓存打开时，http 片段写入同一块磁盘缓存。
 */
public final class ExoMediaSourceHelper {
    public static final String HEADER_FORMAT = "TVBox-Format";
    private static final String TAG = "Tvbox-runtime";

    private static ExoMediaSourceHelper sInstance;

    private final Context mAppContext;
    private OkHttpDataSource.Factory mHttpDataSourceFactory;
    private Cache mCache;
    private OkHttpClient mClient;
    /** 当前这次播放要不要写磁盘缓存。直播由调用方关掉。 */
    private boolean playbackCacheEnabled;
    /** 设置里的缓存上限。和已创建缓存不一致时，下次开播重建。 */
    private long cacheMaxBytes = 512L * 1024 * 1024;
    /** 当前这块缓存实际使用的上限。 */
    private long cacheBuiltBytes;

    private ExoMediaSourceHelper(Context context) {
        mAppContext = context.getApplicationContext();
    }

    public static ExoMediaSourceHelper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (ExoMediaSourceHelper.class) {
                if (sInstance == null) {
                    sInstance = new ExoMediaSourceHelper(context);
                }
            }
        }
        return sInstance;
    }

    public void setOkClient(OkHttpClient client) {
        mClient = client;
        mHttpDataSourceFactory = null;
    }

    public MediaSource getMediaSource(String uri) {
        return getMediaSource(uri, null, false);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers) {
        return getMediaSource(uri, headers, false);
    }

    public MediaSource getMediaSource(String uri, boolean isCache) {
        return getMediaSource(uri, null, isCache);
    }

    public MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache) {
        return getMediaSource(uri, headers, isCache, inferContentType(uri, headers));
    }

    public MediaSource getHlsMediaSource(String uri, Map<String, String> headers) {
        return getMediaSource(uri, headers, false, C.TYPE_HLS);
    }

    private MediaSource getMediaSource(String uri, Map<String, String> headers, boolean isCache, int contentType) {
        Uri contentUri = Uri.parse(uri);
        if ("rtmp".equals(contentUri.getScheme())) {
            return new ProgressiveMediaSource.Factory(new RtmpDataSourceFactory(null))
                    .createMediaSource(MediaItem.fromUri(contentUri));
        } else if ("rtsp".equals(contentUri.getScheme())) {
            return new RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(contentUri));
        }
        DataSource.Factory factory;
        if ((isCache || playbackCacheEnabled) && isNetworkUrl(uri)) {
            factory = getCacheDataSourceFactory();
        } else {
            factory = getDataSourceFactory();
        }
        applyHeaders(headers);
        switch (contentType) {
            case C.TYPE_DASH:
                return new DashMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(contentUri));
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(factory)
                        .setExtractorFactory(new ResyncHlsExtractorFactory())
                        .setLoadErrorHandlingPolicy(new HlsErrorHandlingPolicy())  // 切片快速重试并兼容TS同步异常，保留默认回退
                        .createMediaSource(MediaItem.fromUri(contentUri));
            default:
            case C.TYPE_OTHER:
                return new ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(contentUri));
        }
    }

    private int inferContentType(String fileName, Map<String, String> headers) {
        int formatType = inferFormatContentType(headers);
        if (formatType != C.TYPE_OTHER) {
            return formatType;
        }
        fileName = fileName.toLowerCase();
        if (fileName.contains(".mpd") || fileName.contains("type=mpd") || fileName.contains("type=dash") || fileName.contains("format=mpd") || fileName.contains("format=dash")) {
            return C.TYPE_DASH;
        } else if (isHlsUri(fileName)) {
            return C.TYPE_HLS;
        } else {
            return C.TYPE_OTHER;
        }
    }

    private int inferFormatContentType(Map<String, String> headers) {
        if (headers == null || !headers.containsKey(HEADER_FORMAT)) {
            return C.TYPE_OTHER;
        }
        String format = headers.get(HEADER_FORMAT);
        if (format == null) {
            return C.TYPE_OTHER;
        }
        format = format.trim().toLowerCase();
        if (format.equals("hls") || format.contains("mpegurl") || format.contains("m3u8")) {
            return C.TYPE_HLS;
        }
        if (format.equals("dash") || format.equals("mpd") || format.contains("dash+xml")) {
            return C.TYPE_DASH;
        }
        return C.TYPE_OTHER;
    }

    private boolean isHlsUri(String uri) {
        if (isAudioUri(uri)) {
            return false;
        }
        if (uri.contains("m3u8") || uri.contains("type=hls") || uri.contains("format=hls")) {
            return true;
        }
        Uri parsedUri = Uri.parse(uri);
        String path = parsedUri.getPath();
        if (path == null) {
            return false;
        }
        path = path.toLowerCase();
        return path.endsWith("/live.php") || path.contains("/live/");
    }

    private boolean isAudioUri(String uri) {
        Uri parsedUri = Uri.parse(uri);
        String path = parsedUri.getPath();
        if (path == null) {
            path = uri;
        }
        path = path.toLowerCase();
        return path.endsWith(".mp3")
                || path.endsWith(".m4a")
                || path.endsWith(".aac")
                || path.endsWith(".flac")
                || path.endsWith(".wav")
                || path.endsWith(".ogg")
                || path.endsWith(".opus")
                || path.endsWith(".amr");
    }

    /**
     * 点播开始前设置这次是否缓存，以及上限。上限变了不立刻拆掉正在用的缓存，下次创建媒体源时再建。
     *
     * @param enabled 点播缓存开关。直播传 false
     * @param maxBytes 上限字节数，小于等于 0 时用 512MB
     */
    public void setPlaybackCache(boolean enabled, long maxBytes) {
        playbackCacheEnabled = enabled;
        cacheMaxBytes = maxBytes > 0 ? maxBytes : 512L * 1024 * 1024;
    }

    /**
     * @param uri 播放地址
     * @return http 和 https 才进磁盘缓存，本地文件不重复包一层
     */
    private boolean isNetworkUrl(String uri) {
        if (TextUtils.isEmpty(uri)) {
            return false;
        }
        String lower = uri.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private DataSource.Factory getCacheDataSourceFactory() {
        try {
            ensureCache();
        } catch (RuntimeException ex) {
            Log.e(TAG, "echo-exo-cache-open: " + ex.getMessage());
            return getDataSourceFactory();
        }
        if (mCache == null) {
            return getDataSourceFactory();
        }
        return new CacheDataSource.Factory()
                .setCache(mCache)
                .setUpstreamDataSourceFactory(getDataSourceFactory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    /**
     * 同一目录只能有一块缓存。上限变了先释放旧的，再建新的。
     */
    private synchronized void ensureCache() {
        long desired = cacheMaxBytes > 0 ? cacheMaxBytes : 512L * 1024 * 1024;
        if (mCache != null && cacheBuiltBytes == desired) {
            return;
        }
        releaseCache();
        mCache = newCache(desired);
        cacheBuiltBytes = desired;
        Log.i(TAG, "echo-exo-cache-ready bytes=" + desired);
    }

    private synchronized void releaseCache() {
        Cache cache = mCache;
        mCache = null;
        cacheBuiltBytes = 0;
        if (cache == null) {
            return;
        }
        try {
            cache.release();
        } catch (RuntimeException ex) {
            Log.e(TAG, "echo-exo-cache-release: " + ex.getMessage());
        }
    }

    /**
     * @param maxBytes 淘汰上限
     * @return 新的磁盘缓存
     */
    private Cache newCache(long maxBytes) {
        return new SimpleCache(
                playbackCacheDir(),
                new LeastRecentlyUsedCacheEvictor(maxBytes),
                new ExoDatabaseProvider(mAppContext));
    }

    /**
     * 点播磁盘缓存目录。没有外置缓存目录时用应用私有缓存。
     */
    public File playbackCacheDir() {
        return new File(externalCacheDir(), "exo-video-cache");
    }

    /**
     * 统计目录里已经落下的缓存文件。缓存还没打开时也能算。
     *
     * @return 字节数，目录不存在时为 0
     */
    public long playbackCacheUsedBytes() {
        return folderBytes(playbackCacheDir());
    }

    /**
     * 先放开正在使用的缓存，再删掉目录。正在播放的这一路可能会失败，下次播放会重新建缓存。
     */
    public synchronized void clearPlaybackCache() {
        releaseCache();
        deleteTree(playbackCacheDir());
    }

    private static long folderBytes(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (!file.isDirectory()) {
            return Math.max(0L, file.length());
        }
        File[] children = file.listFiles();
        if (children == null) {
            return 0L;
        }
        long total = 0L;
        for (File child : children) {
            if (child == null) {
                continue;
            }
            total += folderBytes(child);
        }
        return total;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
        }
        if (!file.delete()) {
            Log.i(TAG, "echo-exo-cache-delete-failed: " + file.getAbsolutePath());
        }
    }

    private File externalCacheDir()
    {
        File externalCacheDir = mAppContext.getExternalCacheDir();
        if (externalCacheDir == null){
            externalCacheDir = mAppContext.getCacheDir();
        }
        return externalCacheDir;
    }
    /**
     * Returns a new DataSource factory.
     *
     * @return A new DataSource factory.
     */
    private DataSource.Factory getDataSourceFactory() {
        return new com.google.android.exoplayer2.upstream.DefaultDataSourceFactory(mAppContext, getHttpDataSourceFactory());
    }

    /**
     * Returns a new HttpDataSource factory.
     *
     * @return A new HttpDataSource factory.
     */
    private OkHttpDataSource.Factory getHttpDataSourceFactory() {
        if (mHttpDataSourceFactory == null) {
            OkHttpClient client = mClient != null ? mClient : new OkHttpClient.Builder().build();
            mHttpDataSourceFactory = new OkHttpDataSource.Factory(client);
        }
        return mHttpDataSourceFactory;
    }

    private void applyHeaders(Map<String, String> headers) {
        Map<String, String> requestHeaders = new HashMap<>();
        String userAgent = null;
        if (headers != null && headers.size() > 0) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
                    continue;
                }
                if (HEADER_FORMAT.equalsIgnoreCase(key)) {
                    continue;
                }
                if ("User-Agent".equalsIgnoreCase(key)) {
                    userAgent = value.trim();
                } else {
                    requestHeaders.put(key, value.trim());
                }
            }
        }
        mHttpDataSourceFactory.setUserAgent(userAgent);
        mHttpDataSourceFactory.setDefaultRequestProperties(requestHeaders);
    }

    public void setCache(Cache cache) {
        this.mCache = cache;
    }
}
