package xyz.doikki.videoplayer.exo;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 磁盘缓存键需要切片上的标记，真正访问源站前把这些标记去掉。
 */
final class HintStrippingDataSource implements DataSource {
    /** 实际去拉网络或读本地的数据源。 */
    private final DataSource upstream;

    /**
     * @param upstream 下一层数据源，不能为空
     */
    HintStrippingDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    /**
     * 包一层工厂，让缓存数据源的上游都先剥标记。
     */
    static final class Factory implements DataSource.Factory {
        /** 原始数据源工厂。 */
        private final DataSource.Factory upstream;

        /**
         * @param upstream 原始工厂，不能为空
         */
        Factory(DataSource.Factory upstream) {
            this.upstream = upstream;
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
            return new HintStrippingDataSource(upstream.createDataSource());
        }
    }

    @Override
    public void addTransferListener(@NonNull TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        return upstream.open(PlaybackCacheKeys.stripHints(dataSpec));
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
