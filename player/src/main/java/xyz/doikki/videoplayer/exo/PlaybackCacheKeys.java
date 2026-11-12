package xyz.doikki.videoplayer.exo;

import android.net.Uri;
import android.text.TextUtils;

import com.google.android.exoplayer2.upstream.DataSpec;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把一次播放请求收成稳定的磁盘缓存键。
 * 播放地址里的签名、本地代理外壳每次都会变，不能拿来认「上一集已经看过的那段」。
 */
final class PlaybackCacheKeys {
    /** 写进切片地址、请求发出前会剥掉，只参与缓存键。 */
    static final String QUERY_KEY = "tbkey";
    /** 同一集里的轨道，例如某一档清晰度或音轨。 */
    static final String QUERY_TRACK = "tbtrack";
    /** 切片在这一集里的序号。整段文件没有这个参数。 */
    static final String QUERY_SEQ = "tbseq";

    private static final Set<String> VOLATILE_QUERY = new HashSet<>();

    static {
        Collections.addAll(VOLATILE_QUERY,
                "token", "sign", "signature", "sig", "auth", "auth_key", "authkey",
                "expires", "expire", "timestamp", "t", "e", "ts", "st",
                "wssecret", "wstime", "whsecret", "whtime",
                "upsig", "upid", "um_sign", "nmb",
                "accesskeyid", "securitytoken",
                "x-amz-algorithm", "x-amz-credential", "x-amz-date", "x-amz-expires",
                "x-amz-signedheaders", "x-amz-signature", "x-amz-security-token");
    }

    private PlaybackCacheKeys() {
    }

    /**
     * 剧集标识收成固定长度，避免片名里的特殊字符进缓存键。
     *
     * @param raw 播放页的进度键，可空
     * @return 空串表示这次没有剧集标识
     */
    static String scopeId(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() == 0) {
            return "";
        }
        return shortHash(trimmed, 16);
    }

    /**
     * @param rawScope 这一集的原始标识，可空
     * @param mainUrl  这次开播的主地址，可空
     * @param spec     播放器本次要读的那段
     * @return 磁盘缓存键。同一集、同一段内容下次地址变了也对得上
     */
    static String build(String rawScope, String mainUrl, DataSpec spec) {
        if (spec == null || spec.uri == null) {
            return "empty";
        }
        if (!TextUtils.isEmpty(spec.key)) {
            return spec.key;
        }
        Uri uri = spec.uri;
        String seq = query(uri, QUERY_SEQ);
        if (!TextUtils.isEmpty(seq)) {
            String scope = query(uri, QUERY_KEY);
            if (TextUtils.isEmpty(scope)) {
                scope = scopeId(rawScope);
            }
            if (TextUtils.isEmpty(scope)) {
                return normalizedUrl(uri);
            }
            String track = query(uri, QUERY_TRACK);
            if (TextUtils.isEmpty(track)) {
                track = "main";
            }
            return scope + "/h/" + sanitize(track) + "/" + sanitize(seq);
        }
        String scope = scopeId(rawScope);
        if (!TextUtils.isEmpty(scope) && isCurrentMainFile(uri, mainUrl)) {
            return scope + "/file";
        }
        return normalizedUrl(uri);
    }

    /**
     * 给切片地址加上缓存标记。相对地址和绝对地址都保持原来的形态。
     *
     * @param url   播放列表里的地址，可空
     * @param scope 已经收短的剧集标识
     * @param track 轨道标识
     * @param seq   切片序号
     * @return 带标记的地址；入参为空时原样返回
     */
    static String appendHints(String url, String scope, String track, String seq) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(scope) || TextUtils.isEmpty(seq)) {
            return url;
        }
        if (url.contains(QUERY_SEQ + "=")) {
            return url;
        }
        String fragment = "";
        int hashAt = url.indexOf('#');
        String base = url;
        if (hashAt >= 0) {
            fragment = url.substring(hashAt);
            base = url.substring(0, hashAt);
        }
        String extra = QUERY_KEY + "=" + Uri.encode(scope)
                + "&" + QUERY_TRACK + "=" + Uri.encode(TextUtils.isEmpty(track) ? "main" : track)
                + "&" + QUERY_SEQ + "=" + Uri.encode(seq);
        if (base.contains("?")) {
            if (base.endsWith("?") || base.endsWith("&")) {
                return base + extra + fragment;
            }
            return base + "&" + extra + fragment;
        }
        return base + "?" + extra + fragment;
    }

    /**
     * 网络请求不能带缓存标记，否则源站会把它们当成自己的参数。
     *
     * @param spec 播放器原始请求
     * @return 没有标记时返回原对象
     */
    static DataSpec stripHints(DataSpec spec) {
        if (spec == null || spec.uri == null || !hasHint(spec.uri)) {
            return spec;
        }
        Uri stripped = stripHintParams(spec.uri);
        if (stripped == null || stripped.equals(spec.uri)) {
            return spec;
        }
        return spec.buildUpon().setUri(stripped).build();
    }

    /**
     * 比较两次播放列表是不是同一条资源。签名和参数顺序不算差异。
     */
    static boolean sameLocation(Uri left, Uri right) {
        if (left == null || right == null) {
            return false;
        }
        return normalizedUrl(left).equals(normalizedUrl(right));
    }

    private static boolean isCurrentMainFile(Uri request, String mainUrl) {
        if (request == null || TextUtils.isEmpty(mainUrl)) {
            return false;
        }
        Uri main = Uri.parse(mainUrl);
        if (isPlaylist(request) || isPlaylist(main)) {
            return false;
        }
        return sameLocation(request, main);
    }

    private static boolean isPlaylist(Uri uri) {
        Uri unwrapped = unwrap(uri);
        if (unwrapped == null) {
            return false;
        }
        String path = unwrapped.getPath() == null ? "" : unwrapped.getPath().toLowerCase(Locale.US);
        String text = unwrapped.toString().toLowerCase(Locale.US);
        return path.endsWith(".m3u8") || path.endsWith(".mpd")
                || text.contains("m3u8") || text.contains("type=hls") || text.contains("format=hls")
                || text.contains("type=mpd") || text.contains("format=dash");
    }

    private static String normalizedUrl(Uri uri) {
        Uri unwrapped = unwrap(stripHintParams(uri));
        if (unwrapped == null) {
            return "empty";
        }
        if (!unwrapped.isHierarchical()) {
            return unwrapped.toString();
        }
        Uri.Builder builder = new Uri.Builder()
                .scheme(unwrapped.getScheme())
                .encodedAuthority(unwrapped.getEncodedAuthority())
                .path(unwrapped.getPath());
        List<String> names = new ArrayList<>();
        Set<String> rawNames = unwrapped.getQueryParameterNames();
        if (rawNames != null) {
            names.addAll(rawNames);
        }
        Collections.sort(names);
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (isVolatileQuery(name)) {
                continue;
            }
            List<String> values = unwrapped.getQueryParameters(name);
            if (values == null) {
                continue;
            }
            for (int j = 0; j < values.size(); j++) {
                builder.appendQueryParameter(name, values.get(j));
            }
        }
        return builder.build().toString();
    }

    private static Uri unwrap(Uri uri) {
        Uri current = uri;
        for (int i = 0; i < 3 && current != null; i++) {
            if (!current.isHierarchical()) {
                return current;
            }
            String host = current.getHost();
            boolean local = "127.0.0.1".equals(host) || "localhost".equals(host);
            if (!local) {
                return current;
            }
            String inner = current.getQueryParameter("url");
            if (TextUtils.isEmpty(inner)) {
                inner = current.getQueryParameter("src");
            }
            if (TextUtils.isEmpty(inner)) {
                return current;
            }
            Uri innerUri = Uri.parse(inner);
            String scheme = innerUri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return current;
            }
            current = innerUri;
        }
        return current;
    }

    private static boolean hasHint(Uri uri) {
        if (uri == null || !uri.isHierarchical()) {
            return false;
        }
        return uri.getQueryParameter(QUERY_SEQ) != null
                || uri.getQueryParameter(QUERY_TRACK) != null
                || uri.getQueryParameter(QUERY_KEY) != null;
    }

    private static Uri stripHintParams(Uri uri) {
        if (uri == null || !uri.isHierarchical() || !hasHint(uri)) {
            return uri;
        }
        Set<String> names = uri.getQueryParameterNames();
        Uri.Builder builder = uri.buildUpon().clearQuery();
        if (names != null) {
            List<String> ordered = new ArrayList<>(names);
            for (int i = 0; i < ordered.size(); i++) {
                String name = ordered.get(i);
                if (QUERY_KEY.equals(name) || QUERY_TRACK.equals(name) || QUERY_SEQ.equals(name)) {
                    continue;
                }
                List<String> values = uri.getQueryParameters(name);
                if (values == null) {
                    continue;
                }
                for (int j = 0; j < values.size(); j++) {
                    builder.appendQueryParameter(name, values.get(j));
                }
            }
        }
        return builder.build();
    }

    private static String query(Uri uri, String name) {
        if (uri == null || !uri.isHierarchical() || TextUtils.isEmpty(name)) {
            return "";
        }
        String value = uri.getQueryParameter(name);
        return value == null ? "" : value;
    }

    private static boolean isVolatileQuery(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        if (QUERY_KEY.equals(lower) || QUERY_TRACK.equals(lower) || QUERY_SEQ.equals(lower)) {
            return true;
        }
        if (lower.startsWith("x-amz-")) {
            return true;
        }
        if (VOLATILE_QUERY.contains(lower)) {
            return true;
        }
        return lower.contains("token") || lower.contains("sign") || lower.contains("expire");
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    private static String shortHash(String value, int hexChars) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder(hexChars);
            for (int i = 0; i < bytes.length && builder.length() < hexChars; i++) {
                builder.append(String.format(Locale.US, "%02x", bytes[i] & 0xff));
            }
            if (builder.length() == 0) {
                return Integer.toHexString(value.hashCode());
            }
            return builder.toString();
        } catch (GeneralSecurityException ex) {
            return Integer.toHexString(value.hashCode());
        } catch (java.io.UnsupportedEncodingException ex) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
