package xyz.doikki.videoplayer.exo;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 点播磁盘缓存的外层。夸父盘这类本地代理经常不给文件总长度，
 * Exo 因此整段都不落盘。这里拿掉那个限制，让已经读到的字节照样写入缓存。
 * 直播不会走到这里，调用方在开播前已经关掉缓存。
 */
final class UnknownLengthCacheDataSource implements DataSource {
    private static final String TAG = "Tvbox-runtime";

    /** 真正的缓存数据源。 */
    private final DataSource upstream;
    /** 同一次播放只打一条日志，避免每个分片都刷屏。 */
    private boolean logged;

    /**
     * @param upstream CacheDataSource，不能为空
     */
    UnknownLengthCacheDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    /**
     * @param cacheFactory 已配好缓存目录和缓存键的工厂
     */
    static DataSource.Factory wrap(final DataSource.Factory cacheFactory) {
        return new DataSource.Factory() {
            @NonNull
            @Override
            public DataSource createDataSource() {
                return new UnknownLengthCacheDataSource(cacheFactory.createDataSource());
            }
        };
    }

    @Override
    public void addTransferListener(@NonNull TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    /**
     * 去掉「长度未知就不缓存」。点播代理不给总长度时，已下载的部分仍要留下。
     */
    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        if (dataSpec.isFlagSet(DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN)) {
            if (!logged) {
                logged = true;
                Log.i(TAG, "echo-exo-cache-unknown-length");
            }
            dataSpec = dataSpec.buildUpon()
                    .setFlags(dataSpec.flags & ~DataSpec.FLAG_DONT_CACHE_IF_LENGTH_UNKNOWN)
                    .build();
        }
        return upstream.open(dataSpec);
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        return upstream.read(buffer, offset, length);
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @NonNull
    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        upstream.close();
    }
}
