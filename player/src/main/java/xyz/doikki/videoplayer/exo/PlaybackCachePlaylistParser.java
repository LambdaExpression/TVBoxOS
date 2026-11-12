package xyz.doikki.videoplayer.exo;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist;
import com.google.android.exoplayer2.source.hls.playlist.HlsMultivariantPlaylist;
import com.google.android.exoplayer2.source.hls.playlist.HlsPlaylist;
import com.google.android.exoplayer2.source.hls.playlist.HlsPlaylistParser;
import com.google.android.exoplayer2.source.hls.playlist.HlsPlaylistParserFactory;
import com.google.android.exoplayer2.upstream.ParsingLoadable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 给 HLS 切片打上这一集里的序号。
 * 下次播放地址变了，仍按序号找回已经看过的切片；改写失败就用原列表，播放不中断。
 */
final class PlaybackCachePlaylistParser implements HlsPlaylistParserFactory {
    private static final String TAG = "Tvbox-runtime";

    /** 这一集的原始标识。空则不改播放列表。 */
    private final String rawScope;

    /**
     * @param rawScope 播放页进度键，可空
     */
    PlaybackCachePlaylistParser(String rawScope) {
        this.rawScope = rawScope == null ? "" : rawScope;
    }

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser() {
        return new Rewriter(new HlsPlaylistParser(), null, rawScope);
    }

    @Override
    public ParsingLoadable.Parser<HlsPlaylist> createPlaylistParser(
            HlsMultivariantPlaylist multivariantPlaylist,
            @Nullable HlsMediaPlaylist previousMediaPlaylist) {
        return new Rewriter(
                new HlsPlaylistParser(multivariantPlaylist, previousMediaPlaylist),
                multivariantPlaylist,
                rawScope);
    }

    /**
     * 先按 Exo 自己的规则解析，再只改切片地址上的缓存标记。
     */
    private static final class Rewriter implements ParsingLoadable.Parser<HlsPlaylist> {
        /** Exo 自带解析器。 */
        private final ParsingLoadable.Parser<HlsPlaylist> delegate;
        /** 多码率主列表。单列表点播时为空。 */
        @Nullable
        private final HlsMultivariantPlaylist multivariant;
        /** 这一集的原始标识。 */
        private final String rawScope;

        /**
         * @param delegate      原始解析器
         * @param multivariant  主列表，可空
         * @param rawScope      剧集标识
         */
        Rewriter(ParsingLoadable.Parser<HlsPlaylist> delegate,
                 @Nullable HlsMultivariantPlaylist multivariant,
                 String rawScope) {
            this.delegate = delegate;
            this.multivariant = multivariant;
            this.rawScope = rawScope == null ? "" : rawScope;
        }

        @Override
        public HlsPlaylist parse(Uri uri, InputStream inputStream) throws IOException {
            HlsPlaylist playlist = delegate.parse(uri, inputStream);
            if (!(playlist instanceof HlsMediaPlaylist) || rawScope.length() == 0) {
                return playlist;
            }
            try {
                return tag((HlsMediaPlaylist) playlist, uri);
            } catch (RuntimeException ex) {
                Log.e(TAG, "echo-exo-cache-playlist: " + ex.getMessage());
                return playlist;
            }
        }

        /**
         * 按切片在列表中的序号打标记。初始化段单独占一个固定序号，避免和正片抢同一个键。
         */
        private HlsMediaPlaylist tag(HlsMediaPlaylist playlist, Uri playlistUri) {
            String scope = PlaybackCacheKeys.scopeId(rawScope);
            if (scope.length() == 0) {
                return playlist;
            }
            String track = trackId(playlistUri);
            Map<String, HlsMediaPlaylist.Segment> taggedInits = new HashMap<>();
            List<HlsMediaPlaylist.Segment> segments = new ArrayList<>();
            List<HlsMediaPlaylist.Segment> source = playlist.segments;
            if (source != null) {
                for (int i = 0; i < source.size(); i++) {
                    HlsMediaPlaylist.Segment segment = source.get(i);
                    if (segment == null) {
                        continue;
                    }
                    long sequence = playlist.mediaSequence + i;
                    HlsMediaPlaylist.Segment taggedInit = tagInit(segment.initializationSegment, scope, track, taggedInits);
                    segments.add(copySegment(segment, scope, track, sequence, taggedInit));
                }
            }
            return new HlsMediaPlaylist(
                    playlist.playlistType,
                    playlist.baseUri,
                    playlist.tags,
                    playlist.startOffsetUs,
                    playlist.preciseStart,
                    playlist.startTimeUs,
                    playlist.hasDiscontinuitySequence,
                    playlist.discontinuitySequence,
                    playlist.mediaSequence,
                    playlist.version,
                    playlist.targetDurationUs,
                    playlist.partTargetDurationUs,
                    playlist.hasIndependentSegments,
                    playlist.hasEndTag,
                    playlist.hasProgramDateTime,
                    playlist.protectionSchemes,
                    segments,
                    playlist.trailingParts,
                    playlist.serverControl,
                    playlist.renditionReports);
        }

        /**
         * 清晰度用码率区分，音轨用分组名区分。对不上时退回主列表，避免把音视频写进同一个键。
         */
        private String trackId(Uri playlistUri) {
            if (multivariant == null || playlistUri == null) {
                return "main";
            }
            List<HlsMultivariantPlaylist.Variant> variants = multivariant.variants;
            if (variants != null) {
                for (int i = 0; i < variants.size(); i++) {
                    HlsMultivariantPlaylist.Variant variant = variants.get(i);
                    if (variant == null || variant.url == null || variant.format == null) {
                        continue;
                    }
                    if (variant.format.bitrate > 0 && PlaybackCacheKeys.sameLocation(variant.url, playlistUri)) {
                        return "b" + variant.format.bitrate;
                    }
                }
            }
            String audio = matchRendition(multivariant.audios, playlistUri);
            if (audio != null) {
                return "a" + audio;
            }
            String subtitle = matchRendition(multivariant.subtitles, playlistUri);
            if (subtitle != null) {
                return "s" + subtitle;
            }
            String video = matchRendition(multivariant.videos, playlistUri);
            if (video != null) {
                return "v" + video;
            }
            return "p" + PlaybackCacheKeys.scopeId(playlistUri.toString());
        }

        @Nullable
        private String matchRendition(@Nullable List<HlsMultivariantPlaylist.Rendition> renditions, Uri playlistUri) {
            if (renditions == null) {
                return null;
            }
            for (int i = 0; i < renditions.size(); i++) {
                HlsMultivariantPlaylist.Rendition rendition = renditions.get(i);
                if (rendition == null || rendition.url == null) {
                    continue;
                }
                if (PlaybackCacheKeys.sameLocation(rendition.url, playlistUri)) {
                    if (rendition.groupId != null && rendition.groupId.length() > 0) {
                        return rendition.groupId;
                    }
                    return "rendition" + i;
                }
            }
            return null;
        }

        @Nullable
        private HlsMediaPlaylist.Segment tagInit(
                @Nullable HlsMediaPlaylist.Segment init,
                String scope,
                String track,
                Map<String, HlsMediaPlaylist.Segment> taggedInits) {
            if (init == null || init.url == null) {
                return null;
            }
            HlsMediaPlaylist.Segment cached = taggedInits.get(init.url);
            if (cached != null) {
                return cached;
            }
            HlsMediaPlaylist.Segment tagged = new HlsMediaPlaylist.Segment(
                    PlaybackCacheKeys.appendHints(init.url, scope, track, "init"),
                    init.byteRangeOffset,
                    init.byteRangeLength,
                    init.fullSegmentEncryptionKeyUri,
                    init.encryptionIV);
            taggedInits.put(init.url, tagged);
            return tagged;
        }

        private HlsMediaPlaylist.Segment copySegment(
                HlsMediaPlaylist.Segment segment,
                String scope,
                String track,
                long sequence,
                @Nullable HlsMediaPlaylist.Segment taggedInit) {
            return new HlsMediaPlaylist.Segment(
                    PlaybackCacheKeys.appendHints(segment.url, scope, track, Long.toString(sequence)),
                    taggedInit,
                    segment.title == null ? "" : segment.title,
                    segment.durationUs,
                    segment.relativeDiscontinuitySequence,
                    segment.relativeStartTimeUs,
                    segment.drmInitData,
                    segment.fullSegmentEncryptionKeyUri,
                    segment.encryptionIV,
                    segment.byteRangeOffset,
                    segment.byteRangeLength,
                    segment.hasGapTag,
                    copyParts(segment.parts, scope, track, sequence, taggedInit));
        }

        private List<HlsMediaPlaylist.Part> copyParts(
                @Nullable List<HlsMediaPlaylist.Part> parts,
                String scope,
                String track,
                long sequence,
                @Nullable HlsMediaPlaylist.Segment taggedInit) {
            List<HlsMediaPlaylist.Part> copied = new ArrayList<>();
            if (parts == null || parts.isEmpty()) {
                return copied;
            }
            for (int i = 0; i < parts.size(); i++) {
                HlsMediaPlaylist.Part part = parts.get(i);
                if (part == null || part.url == null) {
                    continue;
                }
                copied.add(new HlsMediaPlaylist.Part(
                        PlaybackCacheKeys.appendHints(part.url, scope, track, sequence + "p" + i),
                        taggedInit,
                        part.durationUs,
                        part.relativeDiscontinuitySequence,
                        part.relativeStartTimeUs,
                        part.drmInitData,
                        part.fullSegmentEncryptionKeyUri,
                        part.encryptionIV,
                        part.byteRangeOffset,
                        part.byteRangeLength,
                        part.hasGapTag,
                        part.isIndependent,
                        part.isPreload));
            }
            return copied;
        }
    }
}
