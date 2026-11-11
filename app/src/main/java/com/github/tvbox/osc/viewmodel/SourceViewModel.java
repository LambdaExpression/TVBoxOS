package com.github.tvbox.osc.viewmodel;

import android.text.TextUtils;

import android.util.Base64;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.github.catvod.net.OkHttp;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.AbsJson;
import com.github.tvbox.osc.bean.AbsSortJson;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.thirdparty.RemoteTVBox;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.SpiderJob;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.lzy.okgo.request.GetRequest;
import com.orhanobut.hawk.Hawk;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;


import java.io.IOException;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
public class SourceViewModel extends ViewModel {
    private static final String PUSH_AGENT = "push_agent";
    private static final String PUSH_FALLBACK = "push_fallback";
    private static final String PUSH_HEADERS_MARKER = "@Headers=";

    public MutableLiveData<AbsSortXml> sortResult;
    public MutableLiveData<AbsXml> listResult;
    public MutableLiveData<AbsXml> searchResult;
    public MutableLiveData<AbsXml> quickSearchResult;
    public MutableLiveData<AbsXml> detailFallbackSearchResult;
    public MutableLiveData<AbsXml> detailResult;
    public MutableLiveData<JSONObject> actionResult;
    public MutableLiveData<JSONObject> playResult;
    public Gson gson;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger playRequestSeq = new AtomicInteger();
    /** 首页分类请求序号。切源或取消后，旧的刷新结果不再写回界面。 */
    private final AtomicInteger sortRequestSeq = new AtomicInteger();
    /** 当前这一轮播放地址请求。切源时取消它，避免旧连接继续占着线程池。 */
    private volatile SpiderJob playJob;
    private final AtomicInteger detailRequestSeq = new AtomicInteger();
    /** 当前这一轮详情请求，和播放请求分开，避免互相把对方的结果丢掉。 */
    private volatile SpiderJob detailJob;

    public SourceViewModel() {
        sortResult = new MutableLiveData<>();
        listResult = new MutableLiveData<>();
        searchResult = new MutableLiveData<>();
        quickSearchResult = new MutableLiveData<>();
        detailFallbackSearchResult = new MutableLiveData<>();
        detailResult = new MutableLiveData<>();
        actionResult = new MutableLiveData<>();
        playResult = new MutableLiveData<>();
        gson=new Gson();
    }

    public static final ExecutorService spThreadPool = Executors.newSingleThreadExecutor();
    private static final ExecutorService httpPrepareThreadPool = Executors.newFixedThreadPool(3);

    //homeContent缓存，最多存储5个sourceKey的AbsSortXml对象
    private static final Map<String, AbsSortXml> sortCache = new LinkedHashMap<String, AbsSortXml>(5, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Entry<String, AbsSortXml> eldest) {
            return size() > 5;
        }
    };

    private static void cacheSort(String sourceKey, AbsSortXml sortXml) {
        attachSortSource(sourceKey, sortXml);
        if (hasActionSort(sortXml)) {
            return;
        }
        persistSort(sourceKey, sortXml);
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && !hasHomeRecVideos(sortXml)) {
            return;
        }
        if (!shouldBypassSortCache(sourceKey, sourceBean)) {
            sortCache.put(sourceKey, sortXml);
        }
    }

    public static void clearSortCache(String sourceKey) {
        sortCache.remove(sourceKey);
    }

    public static void clearRuntimeCache() {
        sortCache.clear();
        extendCache.clear();
        clearPersistedSort();
    }

    private static AbsSortXml attachSortSource(String sourceKey, AbsSortXml sortXml) {
        if (sortXml != null) {
            sortXml.sourceKey = sourceKey;
        }
        return sortXml;
    }

    private void postSortResult(String sourceKey, AbsSortXml sortXml) {
        if (sortXml == null) {
            sortXml = new AbsSortXml();
        }
        sortResult.postValue(attachSortSource(sourceKey, sortXml));
    }

    /**
     * 把这一轮首页结果交回界面。请求已过期就丢掉。
     * 已经显示过缓存时，失败只通知界面结束等待，不用空数据盖掉缓存。
     */
    private void deliverSort(int requestSeq, String sourceKey, AbsSortXml sortXml, boolean hadPreview) {
        if (requestSeq != sortRequestSeq.get()) {
            return;
        }
        if (sortXml == null) {
            if (!hadPreview) {
                postSortResult(sourceKey, null);
                return;
            }
            LOG.i("echo--getSort-refresh-failed--" + sourceKey);
            AbsSortXml keep = new AbsSortXml();
            keep.keepCurrent = true;
            postSortResult(sourceKey, keep);
            return;
        }
        sortXml.fromCache = false;
        sortXml.keepCurrent = false;
        postSortResult(sourceKey, sortXml);
        cacheSort(sourceKey, sortXml);
    }

    /** 首页分类爬虫。和播放不共用那条单线程，切走时直接取消。 */
    private static volatile SpiderJob sortSpiderJob;
    /** 分类列表爬虫。取消后播放不用再等它跑完。 */
    private static volatile SpiderJob listSpiderJob;
    /** 丢掉过期的分类列表结果。 */
    private final AtomicInteger listRequestSeq = new AtomicInteger();

    /** 取消进行中的首页分类刷新，避免切源后旧结果写回来。 */
    public void cancelSortRequest() {
        sortRequestSeq.incrementAndGet();
        cancelJob(sortSpiderJob);
    }

    /**
     * 停掉首页和分类里还在跑的爬虫。播放、详情开始前调用，避免它们排队。
     */
    public static void cancelBackgroundSpiderWork() {
        cancelJob(sortSpiderJob);
        cancelJob(listSpiderJob);
    }

    private static void cancelJob(SpiderJob job) {
        if (job != null) {
            job.cancel();
        }
    }

    /**
     * 扩展地址的网络请求不能在主线程上等待。已经在后台就直接跑。
     */
    private void runOffMain(Runnable action) {
        if (action == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            httpPrepareThreadPool.execute(action);
            return;
        }
        action.run();
    }

    /**
     * 先取出能立刻显示的首页分类和站点推荐。内存优先，没有再读磁盘。
     * 豆瓣热播没有分类缓存、但有热播缓存时，返回空分类，让主页先把热播画出来。
     */
    private AbsSortXml loadSortPreview(String sourceKey, SourceBean sourceBean) {
        if (!shouldBypassSortCache(sourceKey, sourceBean)) {
            AbsSortXml cached = sortCache.get(sourceKey);
            if (cached != null) {
                int homeRec = Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC);
                if (homeRec != 1 || hasHomeRecVideos(cached)) {
                    return cached;
                }
            }
        }
        AbsSortXml persisted = readPersistedSort(sourceKey);
        if (persisted != null) {
            return persisted;
        }
        if (Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 0 && hasDoubanHotCache()) {
            return new AbsSortXml();
        }
        return null;
    }

    private static boolean hasDoubanHotCache() {
        String json = Hawk.get(HawkConfig.HOME_HOT_CACHE, "");
        return json != null && !json.trim().isEmpty();
    }

    private static AbsSortXml copyForPreview(AbsSortXml source) {
        AbsSortXml copy = new AbsSortXml();
        if (source != null) {
            copy.classes = source.classes;
            copy.list = source.list;
            copy.videoList = source.videoList;
        }
        copy.fromCache = true;
        return copy;
    }

    private static String sortCacheHawkKey(String sourceKey) {
        return "home_sort_cache_" + MD5.string2MD5(sourceKey == null ? "" : sourceKey);
    }

    /**
     * 把分类和站点推荐写成一份小 JSON。带点击动作的列表不缓存，避免下次点到过期动作。
     */
    private static void persistSort(String sourceKey, AbsSortXml sortXml) {
        if (TextUtils.isEmpty(sourceKey) || sortXml == null || hasActionSort(sortXml)) {
            return;
        }
        try {
            JsonObject root = new JsonObject();
            JsonArray classes = new JsonArray();
            if (sortXml.classes != null && sortXml.classes.sortList != null) {
                for (MovieSort.SortData sort : sortXml.classes.sortList) {
                    if (sort == null || "my0".equals(sort.id)) {
                        continue;
                    }
                    JsonObject item = new JsonObject();
                    item.addProperty("id", sort.id == null ? "" : sort.id);
                    item.addProperty("name", sort.name == null ? "" : sort.name);
                    if (sort.flag != null) {
                        item.addProperty("flag", sort.flag);
                    }
                    classes.add(item);
                }
            }
            JsonArray videos = new JsonArray();
            if (sortXml.videoList != null) {
                int count = 0;
                for (Movie.Video video : sortXml.videoList) {
                    if (video == null) {
                        continue;
                    }
                    if (count >= 80) {
                        break;
                    }
                    JsonObject item = new JsonObject();
                    item.addProperty("id", video.id == null ? "" : video.id);
                    item.addProperty("name", video.name == null ? "" : video.name);
                    item.addProperty("pic", video.pic == null ? "" : video.pic);
                    item.addProperty("note", video.note == null ? "" : video.note);
                    item.addProperty("sourceKey", TextUtils.isEmpty(video.sourceKey) ? sourceKey : video.sourceKey);
                    if (video.action != null) {
                        item.addProperty("action", video.action);
                    }
                    videos.add(item);
                    count++;
                }
            }
            if (classes.size() == 0 && videos.size() == 0) {
                return;
            }
            root.add("classes", classes);
            root.add("videos", videos);
            Hawk.put(sortCacheHawkKey(sourceKey), root.toString());
            rememberSortCacheKey(sourceKey);
        } catch (Throwable th) {
            LOG.e("echo-home-sort-cache-save: " + sourceKey + " " + th.getMessage());
        }
    }

    private static void rememberSortCacheKey(String sourceKey) {
        ArrayList<String> keys = Hawk.get(HawkConfig.HOME_SORT_CACHE_INDEX, new ArrayList<String>());
        if (keys == null) {
            keys = new ArrayList<>();
        } else {
            keys = new ArrayList<>(keys);
        }
        keys.remove(sourceKey);
        keys.add(sourceKey);
        while (keys.size() > 5) {
            String removed = keys.remove(0);
            if (!TextUtils.isEmpty(removed)) {
                Hawk.delete(sortCacheHawkKey(removed));
            }
        }
        Hawk.put(HawkConfig.HOME_SORT_CACHE_INDEX, keys);
    }

    private static AbsSortXml readPersistedSort(String sourceKey) {
        if (TextUtils.isEmpty(sourceKey)) {
            return null;
        }
        String json = Hawk.get(sortCacheHawkKey(sourceKey), "");
        if (TextUtils.isEmpty(json)) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root == null) {
                return null;
            }
            AbsSortXml sortXml = new AbsSortXml();
            sortXml.sourceKey = sourceKey;
            MovieSort movieSort = new MovieSort();
            movieSort.sortList = new ArrayList<>();
            JsonArray classes = root.has("classes") && root.get("classes").isJsonArray() ? root.getAsJsonArray("classes") : null;
            if (classes != null) {
                for (JsonElement element : classes) {
                    if (element == null || !element.isJsonObject()) {
                        continue;
                    }
                    JsonObject item = element.getAsJsonObject();
                    String id = jsonString(item, "id");
                    String name = jsonString(item, "name");
                    if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) {
                        continue;
                    }
                    MovieSort.SortData sort = new MovieSort.SortData(id, name);
                    sort.flag = jsonString(item, "flag");
                    movieSort.sortList.add(sort);
                }
            }
            sortXml.classes = movieSort;
            ArrayList<Movie.Video> videos = new ArrayList<>();
            JsonArray videoArray = root.has("videos") && root.get("videos").isJsonArray() ? root.getAsJsonArray("videos") : null;
            if (videoArray != null) {
                for (JsonElement element : videoArray) {
                    if (element == null || !element.isJsonObject()) {
                        continue;
                    }
                    JsonObject item = element.getAsJsonObject();
                    Movie.Video video = new Movie.Video();
                    video.id = jsonString(item, "id");
                    video.name = jsonString(item, "name");
                    video.pic = jsonString(item, "pic");
                    video.note = jsonString(item, "note");
                    video.sourceKey = jsonString(item, "sourceKey");
                    if (TextUtils.isEmpty(video.sourceKey)) {
                        video.sourceKey = sourceKey;
                    }
                    String action = jsonString(item, "action");
                    if (!TextUtils.isEmpty(action)) {
                        video.action = action;
                    }
                    if (TextUtils.isEmpty(video.name) && TextUtils.isEmpty(video.id)) {
                        continue;
                    }
                    videos.add(video);
                }
            }
            if (!videos.isEmpty()) {
                sortXml.videoList = videos;
            }
            if (movieSort.sortList.isEmpty() && videos.isEmpty()) {
                return null;
            }
            return sortXml;
        } catch (Throwable th) {
            LOG.e("echo-home-sort-cache-read: " + sourceKey + " " + th.getMessage());
            return null;
        }
    }

    private static String jsonString(JsonObject object, String key) {
        if (object == null || TextUtils.isEmpty(key) || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Throwable th) {
            return "";
        }
    }

    private static void clearPersistedSort() {
        ArrayList<String> keys = Hawk.get(HawkConfig.HOME_SORT_CACHE_INDEX, new ArrayList<String>());
        if (keys != null) {
            for (String sourceKey : keys) {
                if (!TextUtils.isEmpty(sourceKey)) {
                    Hawk.delete(sortCacheHawkKey(sourceKey));
                }
            }
        }
        Hawk.delete(HawkConfig.HOME_SORT_CACHE_INDEX);
    }

    private static boolean hasActionSort(AbsSortXml sortXml) {
        if (sortXml == null) return false;
        if (hasActionVideo(sortXml.videoList)) return true;
        return sortXml.list != null && hasActionVideo(sortXml.list.videoList);
    }

    private static boolean hasHomeRecVideos(AbsSortXml sortXml) {
        return sortXml != null && sortXml.videoList != null && !sortXml.videoList.isEmpty();
    }

    private static boolean hasActionVideo(List<Movie.Video> videos) {
        if (videos == null) return false;
        for (Movie.Video video : videos) {
            if (video != null && video.action != null) return true;
        }
        return false;
    }

    private static boolean shouldBypassSortCache(String sourceKey, SourceBean sourceBean) {
        return isFirstSource(sourceKey) && isDoubanSource(sourceBean);
    }

    private static boolean isFirstSource(String sourceKey) {
        List<SourceBean> sources = ApiConfig.get().getSourceBeanList();
        return !sources.isEmpty() && sources.get(0) != null && sourceKey.equals(sources.get(0).getKey());
    }

    private static boolean isDoubanSource(SourceBean sourceBean) {
        if (sourceBean == null) return false;
        return containsDouban(sourceBean.getKey())
                || containsDouban(sourceBean.getName())
                || containsDouban(sourceBean.getApi())
                || containsDouban(sourceBean.getExt());
    }

    private static boolean containsDouban(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String lower = value.toLowerCase();
        return lower.contains("douban") || value.contains("\u8c46\u74e3");
    }


    // homeContent
    public void getSort(final String sourceKey) {
        if (sourceKey == null) {
            sortResult.postValue(new AbsSortXml());
            return;
        }

        // 优先检查缓存
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            LOG.i("echo--getSort-source-null--" + sourceKey);
            postSortResult(sourceKey, null);
            return;
        }
        String sourceName = sourceBean.getName();
        if (sourceName != null && sourceName.length() <= 3 && sourceName.endsWith("搜")) {
            postSortResult(sourceKey, null);
            return;
        }

        final int requestSeq = sortRequestSeq.incrementAndGet();
        cancelJob(sortSpiderJob);
        int homeRec = Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC);
        // 观看历史不走推荐缓存。内存里已有分类时直接用，避免每次回首页都重拉。
        if (homeRec == 2 && !shouldBypassSortCache(sourceKey, sourceBean)) {
            AbsSortXml cached = sortCache.get(sourceKey);
            if (cached != null) {
                postSortResult(sourceKey, cached);
                return;
            }
        }
        AbsSortXml preview = loadSortPreview(sourceKey, sourceBean);
        final boolean hadPreview = preview != null;
        if (hadPreview) {
            postSortResult(sourceKey, copyForPreview(preview));
        }

        final int type = sourceBean.getType();
        if (type == 3) {
            final SpiderJob job = new SpiderJob();
            sortSpiderJob = job;
            httpPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    job.run(30, new SpiderJob.Task() {
                        @Override
                        public String call() {
                            if (requestSeq != sortRequestSeq.get()) {
                                return "";
                            }
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            return sp.homeContent(true);
                        }
                    }, new SpiderJob.Callback() {
                        @Override
                        public void onResult(String sortJson) {
                            if (requestSeq != sortRequestSeq.get()) {
                                return;
                            }
                            if (sortJson == null || sortJson.length() == 0) {
                                deliverSort(requestSeq, sourceKey, null, hadPreview);
                                return;
                            }
                            final AbsSortXml sortXml = sortJson(sortResult, sortJson);
                            attachSortSource(sourceKey, sortXml);
                            if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
                                AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                    sortXml.videoList = absXml.movie.videoList;
                                    deliverSort(requestSeq, sourceKey, sortXml, hadPreview);
                                } else {
                                    getHomeRecList(sourceBean, null, new HomeRecCallback() {
                                        @Override
                                        public void done(List<Movie.Video> videos) {
                                            sortXml.videoList = videos;
                                            deliverSort(requestSeq, sourceKey, sortXml, hadPreview);
                                        }
                                    });
                                }
                            } else {
                                deliverSort(requestSeq, sourceKey, sortXml, hadPreview);
                            }
                        }

                        @Override
                        public void onTimeout() {
                            LOG.i("echo--getSort-timeout--" + sourceBean.getKey());
                            deliverSort(requestSeq, sourceKey, null, hadPreview);
                        }

                        @Override
                        public void onError(Throwable error) {
                            LOG.e("echo--getSort-error--" + sourceBean.getKey() + "--" + (error == null ? "" : error.getMessage()));
                            deliverSort(requestSeq, sourceKey, null, hadPreview);
                        }
                    });
                }
            });
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag(sourceBean.getKey() + "_sort")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            AbsSortXml sortXml = null;
                            if (type == 0) {
                                String xml = response.body();
                                sortXml = sortXml(sortResult, xml);
                            } else if (type == 1) {
                                String json = response.body();
                                sortXml = sortJson(sortResult, json);
                            }
                            attachSortSource(sourceKey, sortXml);
                            if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1 && sortXml.list != null && sortXml.list.videoList != null && sortXml.list.videoList.size() > 0) {
                                ArrayList<String> ids = new ArrayList<>();
                                for (Movie.Video vod : sortXml.list.videoList) {
                                    ids.add(vod.id);
                                }
                                final AbsSortXml finalSortXml = sortXml;
                                getHomeRecList(sourceBean, ids, new HomeRecCallback() {
                                    @Override
                                    public void done(List<Movie.Video> videos) {
                                        finalSortXml.videoList = videos;
                                        deliverSort(requestSeq, sourceKey, finalSortXml, hadPreview);
                                    }
                                });
                            } else {
                                deliverSort(requestSeq, sourceKey, sortXml, hadPreview);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            deliverSort(requestSeq, sourceKey, null, hadPreview);
                        }
                    });
        }else if (type == 4) {
            runOffMain(new Runnable() {
                @Override
                public void run() {
            if (requestSeq != sortRequestSeq.get()) {
                return;
            }
            String extend=sourceBean.getExt();
            extend=getFixUrl(extend);
            if(URLEncoder.encode(extend).length()<1000){
                GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                        .tag(sourceBean.getKey() + "_sort")
                        .params("filter", "true");
                // 当 extend 不为空且非空字符串时添加参数
                if (extend != null && !extend.isEmpty()) {
                    request.params("extend", extend);
                }
                request.execute(new AbsCallback<String>() {
                            @Override
                            public String convertResponse(okhttp3.Response response) throws Throwable {
                                if (response.body() != null) {
                                    return response.body().string();
                                } else {
                                    throw new IllegalStateException("网络请求错误");
                                }
                            }

                            @Override
                            public void onSuccess(Response<String> response) {
                                String sortJson  = response.body();
                                if (sortJson != null) {
                                    final AbsSortXml sortXml = sortJson(sortResult, sortJson);
                                    attachSortSource(sourceKey, sortXml);
                                    if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
                                        AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                        if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                            sortXml.videoList = absXml.movie.videoList;
                                            deliverSort(requestSeq, sourceKey, sortXml, hadPreview);
                                        } else {
                                            getHomeRecList(sourceBean, null, new HomeRecCallback() {
                                                @Override
                                                public void done(List<Movie.Video> videos) {
                                                    sortXml.videoList = videos;
                                                    deliverSort(requestSeq, sourceKey, sortXml, hadPreview);
                                                }
                                            });
                                        }
                                    } else {
                                        deliverSort(requestSeq, sourceKey, sortXml, hadPreview);
                                    }
                                } else {
                                    deliverSort(requestSeq, sourceKey, null, hadPreview);
                                }
                            }

                            @Override
                            public void onError(Response<String> response) {
                                super.onError(response);
                                deliverSort(requestSeq, sourceKey, null, hadPreview);
                            }
                        });
            }else {
                try {
                    Map<String, String> params = new HashMap<>();
                    params.put("filter","true");
                    if (extend != null && !extend.isEmpty()) {
                        params.put("extend",extend);
                    }
                    RemoteTVBox.post(sourceBean.getApi(), params, new okhttp3.Callback() {
                        @Override
                        public void onFailure(@NonNull Call call, IOException e) {
                            LOG.i("echo--getSort-post-failed--" + sourceKey + "--" + (e == null ? "" : e.getMessage()));
                            deliverSort(requestSeq, sourceKey, null, hadPreview);
                        }

                        @Override
                        public void onResponse(@NonNull Call call, @NonNull okhttp3.Response response) throws IOException {
                            if (response.body() == null) {
                                deliverSort(requestSeq, sourceKey, null, hadPreview);
                                return;
                            }
                            String sortJson = response.body().string();
                            final AbsSortXml sortXml = sortJson(sortResult, sortJson);
                            attachSortSource(sourceKey, sortXml);
                            if (sortXml != null && Hawk.get(HawkConfig.HOME_REC, HawkConfig.DEFAULT_HOME_REC) == 1) {
                                AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                                if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                                    sortXml.videoList = absXml.movie.videoList;
                                }
                            }
                            deliverSort(requestSeq, sourceKey, sortXml, hadPreview);
                        }
                    });
                } catch (Exception ex) {
                    LOG.e("echo--getSort-post-error--" + sourceKey + "--" + ex.getMessage());
                    deliverSort(requestSeq, sourceKey, null, hadPreview);
                }
            }
                }
            });
        } else {
            deliverSort(requestSeq, sourceKey, null, hadPreview);
        }
    }
    // categoryContent
    public void getList(MovieSort.SortData sortData, int page) {
        if (sortData == null) {
            LOG.i("echo-getList-sortData-null");
            listResult.postValue(null);
            return;
        }
        SourceBean homeSourceBean = ApiConfig.get().getHomeSourceBean();
        if (homeSourceBean == null) {
            LOG.i("echo-getList-source-null");
            listResult.postValue(null);
            return;
        }
        int type = homeSourceBean.getType();
        if (type == 3) {
            final int requestSeq = listRequestSeq.incrementAndGet();
            cancelJob(listSpiderJob);
            final SpiderJob job = new SpiderJob();
            listSpiderJob = job;
            int timeout = homeSourceBean.getPlayTimeoutSeconds();
            if (timeout <= 0) {
                timeout = 20;
            }
            final int listTimeout = timeout;
            httpPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    job.run(listTimeout, new SpiderJob.Task() {
                        @Override
                        public String call() {
                            if (requestSeq != listRequestSeq.get()) {
                                return "";
                            }
                            Spider sp = ApiConfig.get().getCSP(homeSourceBean);
                            return sp.categoryContent(sortData.id, page + "", true, sortData.filterSelect);
                        }
                    }, new SpiderJob.Callback() {
                        @Override
                        public void onResult(String json) {
                            if (requestSeq != listRequestSeq.get()) {
                                return;
                            }
                            if (json != null && json.length() > 0) {
                                json(listResult, json, homeSourceBean.getKey());
                            } else {
                                listResult.postValue(null);
                            }
                        }

                        @Override
                        public void onTimeout() {
                            LOG.i("echo--getList-timeout--" + homeSourceBean.getKey());
                            if (requestSeq == listRequestSeq.get()) {
                                listResult.postValue(null);
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            LOG.e("echo--getList-error--" + homeSourceBean.getKey() + "--" + (error == null ? "" : error.getMessage()));
                            if (requestSeq == listRequestSeq.get()) {
                                listResult.postValue(null);
                            }
                        }
                    });
                }
            });
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(homeSourceBean.getApi())
                    .tag(homeSourceBean.getApi())
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("t", sortData.id)
                    .params("pg", page)
                    .params(sortData.filterSelect)
                    .params("f", (sortData.filterSelect == null || sortData.filterSelect.size() <= 0) ? "" : new JSONObject(sortData.filterSelect).toString())
                    .execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(listResult, xml, homeSourceBean.getKey());
                            } else {
                                String json = response.body();
                                json(listResult, json, homeSourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            listResult.postValue(null);
                        }
                    });
        }else if (type == 4) {
            runOffMain(new Runnable() {
                @Override
                public void run() {
            String ext= "";
            String extend=homeSourceBean.getExt();
            extend=getFixUrl(extend);
            if (sortData.filterSelect != null && sortData.filterSelect.size() > 0) {
                try {
                    String selectExt = new JSONObject(sortData.filterSelect).toString();
                    ext = Base64.encodeToString(selectExt.getBytes("UTF-8"), Base64.DEFAULT |  Base64.NO_WRAP);
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }else {
                ext = Base64.encodeToString("{}".getBytes(), Base64.DEFAULT |  Base64.NO_WRAP);
            }

            GetRequest<String> request = OkGo.<String>get(homeSourceBean.getApi())
                    .tag(homeSourceBean.getApi())
                    .params("ac", "detail")
                    .params("filter", "true")
                    .params("t", sortData.id)
                    .params("pg", page)
                    .params("ext", ext);
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            try {
                                if (response.body() != null) {
                                    return response.body().string();
                                } else {
                                    throw new IllegalStateException("网络请求错误，response body 为 null");
                                }
                            } catch (Exception e) {
                                LOG.i("echo-list: convertResponse error"+ e.getMessage());
                                throw e;  // 重新抛出异常
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String json = response.body();
//                            LOG.i("echo-list: " + json);
                            json(listResult, json, homeSourceBean.getKey());
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            listResult.postValue(null);
                        }
                    });
                }
            });
        } else {
            listResult.postValue(null);
        }
    }

    /** Loads a folder/category for a specific source (used by inline search folders). */
    public void getList(String sourceKey, String id) {
        final SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null || TextUtils.isEmpty(id)) {
            listResult.postValue(null);
            return;
        }
        final int type = sourceBean.getType();
        if (type == 3) {
            final int requestSeq = listRequestSeq.incrementAndGet();
            cancelJob(listSpiderJob);
            final SpiderJob job = new SpiderJob();
            listSpiderJob = job;
            int timeout = sourceBean.getPlayTimeoutSeconds();
            if (timeout <= 0) {
                timeout = 20;
            }
            final int listTimeout = timeout;
            httpPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    job.run(listTimeout, new SpiderJob.Task() {
                        @Override
                        public String call() {
                            if (requestSeq != listRequestSeq.get()) {
                                return "";
                            }
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            return sp.categoryContent(id, "1", true, new HashMap<String, String>());
                        }
                    }, new SpiderJob.Callback() {
                        @Override
                        public void onResult(String json) {
                            if (requestSeq != listRequestSeq.get()) {
                                return;
                            }
                            if (json != null && json.length() > 0) {
                                json(listResult, json, sourceBean.getKey());
                            } else {
                                listResult.postValue(null);
                            }
                        }

                        @Override
                        public void onTimeout() {
                            if (requestSeq == listRequestSeq.get()) {
                                listResult.postValue(null);
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            LOG.e("echo--getFolderList-error--" + sourceBean.getKey() + "--" + (error == null ? "" : error.getMessage()));
                            if (requestSeq == listRequestSeq.get()) {
                                listResult.postValue(null);
                            }
                        }
                    });
                }
            });
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag(sourceBean.getKey() + "_folder")
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("t", id)
                    .params("pg", 1)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) return response.body().string();
                            throw new IllegalStateException("网络请求错误");
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) xml(listResult, response.body(), sourceBean.getKey());
                            else json(listResult, response.body(), sourceBean.getKey());
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            listResult.postValue(null);
                        }
                    });
        } else if (type == 4) {
            runOffMain(new Runnable() {
                @Override
                public void run() {
            String extend = getFixUrl(sourceBean.getExt());
            GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                    .tag(sourceBean.getKey() + "_folder")
                    .params("ac", "detail")
                    .params("filter", "true")
                    .params("t", id)
                    .params("pg", 1)
                    .params("ext", Base64.encodeToString("{}".getBytes(), Base64.DEFAULT | Base64.NO_WRAP));
            if (!TextUtils.isEmpty(extend)) request.params("extend", extend);
            request.execute(new AbsCallback<String>() {
                @Override
                public String convertResponse(okhttp3.Response response) throws Throwable {
                    if (response.body() != null) return response.body().string();
                    throw new IllegalStateException("网络请求错误");
                }

                @Override
                public void onSuccess(Response<String> response) {
                    json(listResult, response.body(), sourceBean.getKey());
                }

                @Override
                public void onError(Response<String> response) {
                    super.onError(response);
                    listResult.postValue(null);
                }
            });
                }
            });
        } else {
            listResult.postValue(null);
        }
    }

    interface HomeRecCallback {
        void done(List<Movie.Video> videos);
    }
//    homeVideoContent
    void getHomeRecList(SourceBean sourceBean, ArrayList<String> ids, HomeRecCallback callback) {
        if (sourceBean == null || callback == null) {
            if (callback != null) {
                callback.done(null);
            }
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            final int requestSeq = sortRequestSeq.get();
            final SpiderJob job = new SpiderJob();
            sortSpiderJob = job;
            httpPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    if (requestSeq != sortRequestSeq.get()) {
                        callback.done(null);
                        return;
                    }
                    job.run(20, new SpiderJob.Task() {
                        @Override
                        public String call() {
                            if (requestSeq != sortRequestSeq.get()) {
                                return "";
                            }
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            return sp.homeVideoContent();
                        }
                    }, new SpiderJob.Callback() {
                        @Override
                        public void onResult(String sortJson) {
                            if (requestSeq != sortRequestSeq.get()) {
                                return;
                            }
                            if (sortJson == null || sortJson.length() == 0) {
                                callback.done(null);
                                return;
                            }
                            AbsXml absXml = json(null, sortJson, sourceBean.getKey());
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        }

                        @Override
                        public void onTimeout() {
                            if (requestSeq != sortRequestSeq.get()) {
                                return;
                            }
                            LOG.i("echo--getHomeRecList-timeout--" + sourceBean.getKey());
                            callback.done(null);
                        }

                        @Override
                        public void onError(Throwable error) {
                            if (requestSeq != sortRequestSeq.get()) {
                                return;
                            }
                            LOG.e("echo--getHomeRecList-error--" + sourceBean.getKey() + "--" + (error == null ? "" : error.getMessage()));
                            callback.done(null);
                        }
                    });
                }
            });
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .tag("detail")
                    .params("ac", sourceBean.getType() == 0 ? "videolist" : "detail")
                    .params("ids", TextUtils.join(",", ids))
                    .execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            AbsXml absXml;
                            if (sourceBean.getType() == 0) {
                                String xml = response.body();
                                absXml = xml(null, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                absXml = json(null, json, sourceBean.getKey());
                            }
                            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null) {
                                callback.done(absXml.movie.videoList);
                            } else {
                                callback.done(null);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            callback.done(null);
                        }
                    });
        } else {
            callback.done(null);
        }
    }
    // detailContent
    public void getDetail(String sourceKey, String urlid) {
        getDetail(sourceKey, urlid, false);
    }

    public void getDetail(String sourceKey, String urlid, boolean fallback) {
        // 新详情开始前停掉上一轮，避免换源时旧的 detailContent 继续占连接。
        sortRequestSeq.incrementAndGet();
        listRequestSeq.incrementAndGet();
        cancelBackgroundSpiderWork();
        final int requestSeq = detailRequestSeq.incrementAndGet();
        cancelDetailJob();
        OkGo.getInstance().cancelTag("detail");
        if (urlid == null) {
            urlid = "";
        }
        if (urlid.startsWith("push://") && ApiConfig.get().getSource(PUSH_AGENT) != null) {
            String pushUrl = urlid.substring(7);
            if (pushUrl.startsWith("b64:")) {
                try {
                    pushUrl = new String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            } else {
                pushUrl = URLDecoder.decode(pushUrl);
            }
            sourceKey = isCastPushUrl(pushUrl) ? PUSH_FALLBACK : PUSH_AGENT;
            urlid = pushUrl;
        } else if (PUSH_AGENT.equals(sourceKey) && isCastPushUrl(urlid)) {
            sourceKey = PUSH_FALLBACK;
        }
        String id = urlid;
    
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (isPushFallback(sourceKey, sourceBean)) {
            detailResult.postValue(createPushDetail(urlid, sourceKey));
            return;
        }
        if (sourceBean == null) {
            LOG.i("echo--getDetail-source-null--" + sourceKey);
            AbsXml data = new AbsXml();
            data.sourceKey = sourceKey;
            detailResult.postValue(data);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            final SpiderJob job = new SpiderJob();
            detailJob = job;
            final boolean detailFallback = fallback;
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    if (requestSeq != detailRequestSeq.get()) {
                        return;
                    }
                    job.run(detailFallback ? 6 : 30, new SpiderJob.Task() {
                        @Override
                        public String call() {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            List<String> ids = new ArrayList<>();
                            ids.add(id);
                            try {
                                return sp.detailContent(ids);
                            } catch (Exception e) {
                                LOG.i("echo--getDetail--error: " + e.getMessage());
                                return "";
                            }
                        }
                    }, new SpiderJob.Callback() {
                        @Override
                        public void onResult(String value) {
                            if (requestSeq != detailRequestSeq.get()) {
                                return;
                            }
                            json(detailResult, value, sourceBean.getKey());
                        }

                        @Override
                        public void onTimeout() {
                            if (requestSeq != detailRequestSeq.get()) {
                                return;
                            }
                            LOG.i("echo--getDetail--timeout source=" + sourceBean.getKey() + ", id=" + id);
                            json(detailResult, "", sourceBean.getKey());
                        }

                        @Override
                        public void onError(Throwable error) {
                            if (requestSeq != detailRequestSeq.get()) {
                                return;
                            }
                            LOG.e("echo--getDetail--error source=" + sourceBean.getKey() + ", id=" + id + ", " + error.getMessage());
                            json(detailResult, "", sourceBean.getKey());
                        }
                    });
                }
            });
        } else if (type == 0 || type == 1|| type == 4) {
            final boolean detailFallback = fallback;
            runOffMain(new Runnable() {
                @Override
                public void run() {
            if (requestSeq != detailRequestSeq.get()) {
                return;
            }
            String extend=sourceBean.getExt();
            extend=detailFallback ? getFixUrl(extend, 6) : getFixUrl(extend);

            GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                    .tag("detail")
                    .params("ac", type == 0 ? "videolist" : "detail")
                    .params("ids", id);
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {

                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(detailResult, xml, sourceBean.getKey());
                            } else {
                                String json = response.body();
                                LOG.i(json);
                                json(detailResult, json, sourceBean.getKey());
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            json(detailResult, "", sourceBean.getKey());
                        }
                    });
                }
            });
        } else {
            AbsXml data = new AbsXml();
            data.sourceKey = sourceKey;
            detailResult.postValue(data);
        }
    }

    public void action(String sourceKey, String action) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null || action == null) {
            actionResult.postValue(null);
            return;
        }
        if (sourceBean.getType() == 3) {
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Spider sp = ApiConfig.get().getCSP(sourceBean);
                        String json = sp.action(action);
                        actionResult.postValue(TextUtils.isEmpty(json) ? null : new JSONObject(json));
                    } catch (Throwable th) {
                        th.printStackTrace();
                        actionResult.postValue(null);
                    }
                }
            });
        } else {
            actionResult.postValue(null);
        }
    }

    // searchContent
    public void getSearch(String sourceKey, String wd) {
        getSearch(sourceKey, wd, "");
    }

    public void getSearch(String sourceKey, String wd, String searchToken) {
        getSearch(sourceKey, wd, searchToken, searchResult, "search");
    }

    public void getDetailFallbackSearch(String sourceKey, String wd, String searchToken) {
        getSearch(sourceKey, wd, searchToken, detailFallbackSearchResult, "detail_fallback_search");
    }

    private void getSearch(String sourceKey, String wd, String searchToken, MutableLiveData<AbsXml> result, String requestTag) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            postEmptySearchResult(result, sourceKey, searchToken);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                String search = sp.searchContent(wd, false);
//                LOG.i("echo--searchContent--result:" + search);
                if(!TextUtils.isEmpty(search)){
                    json(result, search, sourceBean.getKey(), searchToken);
                } else {
                    json(result, "", sourceBean.getKey(), searchToken);
                }
            } catch (Throwable th) {
                th.printStackTrace();
                json(result, "", sourceBean.getKey(), searchToken);
            }
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params(type == 1 ? "ac" : null, type == 1 ? "detail" : null)
                    .tag(requestTag)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(result, xml, sourceBean.getKey(), searchToken);
                            } else {
                                String json = response.body();
                                json(result, json, sourceBean.getKey(), searchToken);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            postEmptySearchResult(result, sourceBean.getKey(), searchToken);
                        }
                    });
        }else if (type == 4) {
            final String searchWd = wd;
            httpPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
            String extend=sourceBean.getExt();
            extend=getFixUrlDirect(extend);
            String queryWd = searchWd;
            try {
                queryWd=URLEncoder.encode(queryWd, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                    .tag(requestTag)
                    .params("wd", queryWd)
                    .params("ac" ,"detail")
                    .params("quick" ,"false");
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() != null) {
                            return response.body().string();
                        } else {
                            LOG.i("echo-t4 search-网络请求错误");
                            throw new IllegalStateException("网络请求错误");
                        }
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                            String json = response.body();
//                            LOG.i("echo-t4 search onSuccess"+json);
                            json(result, json, sourceBean.getKey(), searchToken);
                    }

                    @Override
                    public void onError(Response<String> response) {
                        LOG.i("echo-t4 search-onError");
                        super.onError(response);
                        postEmptySearchResult(result, sourceBean.getKey(), searchToken);
                    }
                });
                }
            });
        } else {
            postEmptySearchResult(result, sourceBean.getKey(), searchToken);
        }
    }
    // searchContent
    public void getQuickSearch(String sourceKey, String wd) {
        getQuickSearch(sourceKey, wd, "");
    }

    public void getQuickSearch(String sourceKey, String wd, String searchToken) {
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        if (sourceBean == null) {
            postEmptySearchResult(quickSearchResult, sourceKey, searchToken);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            try {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                json(quickSearchResult, sp.searchContent(wd, true), sourceBean.getKey(), searchToken);
            } catch (Throwable th) {
                th.printStackTrace();
                postEmptySearchResult(quickSearchResult, sourceBean.getKey(), searchToken);
            }
        } else if (type == 0 || type == 1) {
            OkGo.<String>get(sourceBean.getApi())
                    .params("wd", wd)
                    .params(type == 1 ? "ac" : null, type == 1 ? "detail" : null)
                    .tag("quick_search")
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            if (type == 0) {
                                String xml = response.body();
                                xml(quickSearchResult, xml, sourceBean.getKey(), searchToken);
                            } else {
                                String json = response.body();
                                json(quickSearchResult, json, sourceBean.getKey(), searchToken);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            postEmptySearchResult(quickSearchResult, sourceBean.getKey(), searchToken);
                        }
                    });
        }else if (type == 4) {
            final String searchWd = wd;
            httpPrepareThreadPool.execute(new Runnable() {
                @Override
                public void run() {
            String extend=sourceBean.getExt();
            extend=getFixUrlDirect(extend);

            GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                    .tag("search")
                    .params("wd", searchWd)
                    .params("ac" ,"detail")
                    .params("quick" ,"true");
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() != null) {
                            return response.body().string();
                        } else {
                            throw new IllegalStateException("网络请求错误");
                        }
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        String json = response.body();
                        LOG.i(json);
                        json(quickSearchResult, json, sourceBean.getKey(), searchToken);
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        postEmptySearchResult(quickSearchResult, sourceBean.getKey(), searchToken);
                    }
                });
                }
            });
        } else {
            postEmptySearchResult(quickSearchResult, sourceBean.getKey(), searchToken);
        }
    }
    // playerContent
    public void getPlay(String sourceKey, String playFlag, String progressKey, String url, String subtitleKey) {
        // 先停掉上一轮。序号在取消之后再加，避免把这次新请求也标成过期。
        sortRequestSeq.incrementAndGet();
        listRequestSeq.incrementAndGet();
        cancelBackgroundSpiderWork();
        cancelPlayRequest();
        final int requestSeq = playRequestSeq.incrementAndGet();
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        boolean pushFallback = isPushFallback(sourceKey, sourceBean);
        PushUrl pushUrl = pushFallback ? parsePushUrl(url) : createPushUrl(url);
        String requestUrl = pushUrl.url;
        if (pushFallback) {
            postPlayResult(requestSeq, createPushPlayResult(url, pushUrl, progressKey, subtitleKey, playFlag));
            return;
        }
        if (sourceBean == null) {
            LOG.i("echo--getPlay-source-null--" + sourceKey);
            postPlayResult(requestSeq, null);
            return;
        }
        int type = sourceBean.getType();
        if (type == 3) {
            final SpiderJob job = new SpiderJob();
            playJob = job;
            spThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    if (requestSeq != playRequestSeq.get()) {
                        return;
                    }
                    job.run(sourceBean.getPlayTimeoutSeconds(), new SpiderJob.Task() {
                        @Override
                        public String call() {
                            Spider sp = ApiConfig.get().getCSP(sourceBean);
                            if (TextUtils.isEmpty(requestUrl)) return "";
                            try {
                                LOG.i("echo--getPlay--id: " + requestUrl);
                                return sp.playerContent(playFlag, requestUrl, ApiConfig.get().getVipParseFlags());
                            } catch (Exception e) {
                                LOG.i("echo--getPlay--error: " + e.getMessage());
                                return "";
                            }
                        }
                    }, new SpiderJob.Callback() {
                        @Override
                        public void onResult(String value) {
                            if (requestSeq != playRequestSeq.get()) {
                                return;
                            }
                            LOG.i("echo--getPlay--result:" + value);
                            postPlayJson(requestSeq, value, url, pushUrl, progressKey, subtitleKey, playFlag, sourceBean, requestUrl);
                        }

                        @Override
                        public void onTimeout() {
                            if (requestSeq != playRequestSeq.get()) {
                                return;
                            }
                            LOG.i("echo--getPlay--timeout source=" + sourceBean.getKey());
                            postPlayResult(requestSeq, null);
                        }

                        @Override
                        public void onError(Throwable error) {
                            if (requestSeq != playRequestSeq.get()) {
                                return;
                            }
                            LOG.e("echo--getPlay--error source=" + sourceBean.getKey() + ", " + error.getMessage());
                            postPlayResult(requestSeq, null);
                        }
                    });
                }
            });
        } else if (type == 0 || type == 1) {
            JSONObject result = new JSONObject();
            try {
                result.put("key", url);
                String playUrl = sourceBean.getPlayerUrl().trim();
                if (DefaultConfig.isVideoFormat(requestUrl) && playUrl.isEmpty()) {
                    result.put("parse", 0);
                    result.put("url", requestUrl);
                } else {
                    result.put("parse", 1);
                    result.put("url", requestUrl);
                }
                mergePushHeaders(result, pushUrl);
                result.put("proKey", progressKey);
                result.put("subtKey", subtitleKey);
                result.put("playUrl", playUrl);
                result.put("flag", playFlag);
                postPlayResult(requestSeq, result);
            } catch (Throwable th) {
                th.printStackTrace();
                postPlayResult(requestSeq, null);
            }
        } else if (type == 4) {
            runOffMain(new Runnable() {
                @Override
                public void run() {
            if (requestSeq != playRequestSeq.get()) {
                return;
            }
            String extend=sourceBean.getExt();
            extend=getFixUrl(extend);

            GetRequest<String> request = OkGo.<String>get(sourceBean.getApi())
                    .tag("play")
                    .params("play", requestUrl)
                    .params("flag" ,playFlag);
            // 当 extend 不为空且非空字符串时添加参数
            if (extend != null && !extend.isEmpty()) {
                request.params("extend", extend);
            }
            request.execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() != null) {
                            return response.body().string();
                        } else {
                            throw new IllegalStateException("网络请求错误");
                        }
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        String json = response.body();
                        LOG.i(json);
                        try {
                            JSONObject result = normalizePlayerResult(new JSONObject(json));
                            result.put("key", url);
                            mergePushHeaders(result, pushUrl);
                            result.put("proKey", progressKey);
                            result.put("subtKey", subtitleKey);
                            if (!result.has("flag"))
                                result.put("flag", playFlag);
                            postPlayResult(requestSeq, result);
                        } catch (Throwable th) {
                            th.printStackTrace();
                            postPlayResult(requestSeq, null);
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        postPlayResult(requestSeq, null);
                    }
                });
                }
            });
        }else {
            postPlayResult(requestSeq, null);
        }
    }

    /**
     * 丢掉当前播放地址请求的结果，并断开这轮爬虫连接。
     * 只加序号不够：上一轮 playerContent 仍会占着线程和 socket。
     */
    public void cancelPlayRequest() {
        playRequestSeq.incrementAndGet();
        cancelPlayJob();
        OkGo.getInstance().cancelTag("play");
    }

    private void cancelPlayJob() {
        SpiderJob job = playJob;
        if (job != null) {
            job.cancel();
        }
    }

    private void cancelDetailJob() {
        SpiderJob job = detailJob;
        if (job != null) {
            job.cancel();
        }
    }

    private void postPlayJson(int requestSeq, String json, String url, PushUrl pushUrl, String progressKey, String subtitleKey, String playFlag, SourceBean sourceBean, String requestUrl) {
        try {
            if (!TextUtils.isEmpty(json)) {
                JSONObject result = normalizePlayerResult(new JSONObject(json));
                result.put("key", url);
                mergePushHeaders(result, pushUrl);
                result.put("proKey", progressKey);
                result.put("subtKey", subtitleKey);
                if (!result.has("flag"))
                    result.put("flag", playFlag);
                if (TextUtils.isEmpty(result.optString("url", "")) && shouldDirectPlay(sourceBean, requestUrl)) {
                    postPlayResult(requestSeq, createDirectPlayResult(url, pushUrl, progressKey, subtitleKey, playFlag));
                } else {
                    postPlayResult(requestSeq, result);
                }
            } else {
                postPlayResult(requestSeq, null);
            }
        } catch (Throwable th) {
            LOG.e("echo--getPlay--parse source=" + sourceBean.getKey() + ", " + th.getMessage());
            postPlayResult(requestSeq, null);
        }
    }

    private boolean shouldDirectPlay(SourceBean sourceBean, String requestUrl) {
        return sourceBean != null
                && !TextUtils.isEmpty(requestUrl)
                && (requestUrl.startsWith("http://") || requestUrl.startsWith("https://"));
    }

    private JSONObject createDirectPlayResult(String rawUrl, PushUrl pushUrl, String progressKey, String subtitleKey, String playFlag) {
        try {
            JSONObject result = new JSONObject();
            result.put("key", rawUrl);
            result.put("proKey", progressKey);
            result.put("subtKey", subtitleKey);
            result.put("flag", playFlag);
            result.put("parse", 0);
            result.put("jx", 0);
            result.put("url", pushUrl.url);
            mergePushHeaders(result, pushUrl);
            LOG.i("echo--getPlay--direct:" + pushUrl.url);
            return result;
        } catch (Throwable th) {
            th.printStackTrace();
            return null;
        }
    }

    private JSONObject normalizePlayerResult(JSONObject result) {
        if (result == null) return null;
        try {
            String playUrl = result.optString("playUrl", "");
            String url = result.optString("url", "");
            if (TextUtils.isEmpty(url)) return result;
            if (url.startsWith("[") && url.endsWith("]")) {
                JSONArray array = new JSONArray(url);
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.get(i);
                    if (item instanceof String) {
                        String str = (String) item;
                        if (str.startsWith("proxy://")) {
                            str = DefaultConfig.checkReplaceProxy(str);
                            array.put(i, str);
                        } else if (str.startsWith("video://")) {
                            str = str.substring(8);
                            array.put(i, str);
                        }
                    }
                }
                result.put("url", array.toString());
                result.put("parse", 0);
                return result;
            }
            if (url.startsWith("video://")) {
                url = url.substring(8);
                result.put("url", url);
                result.put("parse", 1);
            } else if (url.startsWith("proxy://")) {
                url = DefaultConfig.checkReplaceProxy(url);
                result.put("url", url);
                result.put("parse", 0);
            } else if (playUrl.length() == 0
                    && DefaultConfig.isVideoFormat(url)
                    && !result.has("parse")
                    && !result.has("jx")) {
                result.put("parse", 0);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return result;
    }

    private boolean isPushFallback(String sourceKey, SourceBean sourceBean) {
        return PUSH_FALLBACK.equals(sourceKey) || (sourceBean != null && PUSH_AGENT.equals(sourceBean.getKey()) && sourceBean.getType() == -1);
    }

    private boolean isCastPushUrl(String url) {
        return !TextUtils.isEmpty(url) && url.contains(PUSH_HEADERS_MARKER);
    }

    private AbsXml createPushDetail(String url, String sourceKey) {
        AbsXml data = new AbsXml();
        data.sourceKey = sourceKey;
        Movie movie = new Movie();
        movie.videoList = new ArrayList<>();
        Movie.Video video = new Movie.Video();
        video.id = url;
        video.name = url;
        video.type = "推送";
        video.sourceKey = sourceKey;
        video.urlBean = new Movie.Video.UrlBean();
        video.urlBean.infoList = new ArrayList<>();
        Movie.Video.UrlBean.UrlInfo urlInfo = new Movie.Video.UrlBean.UrlInfo();
        urlInfo.flag = "推送";
        urlInfo.urls = "播放$" + url;
        urlInfo.beanList = new ArrayList<>();
        urlInfo.beanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean("播放", url));
        video.urlBean.infoList.add(urlInfo);
        movie.videoList.add(video);
        data.movie = movie;
        return data;
    }

    private JSONObject createPushPlayResult(String rawUrl, PushUrl pushUrl, String progressKey, String subtitleKey, String playFlag) {
        try {
            JSONObject result = new JSONObject();
            result.put("key", rawUrl);
            result.put("proKey", progressKey);
            result.put("subtKey", subtitleKey);
            result.put("flag", playFlag);
            result.put("parse", 0);
            result.put("url", pushUrl.url);
            mergePushHeaders(result, pushUrl);
            return result;
        } catch (Throwable th) {
            th.printStackTrace();
            return null;
        }
    }

    private void mergePushHeaders(JSONObject result, PushUrl pushUrl) {
        if (result == null || pushUrl == null || pushUrl.headers.isEmpty()) return;
        try {
            JSONObject header = result.optJSONObject("header");
            if (header == null) header = result.optJSONObject("headers");
            if (header == null) header = new JSONObject();
            for (String key : pushUrl.headers.keySet()) {
                header.put(key, pushUrl.headers.get(key));
            }
            result.put("header", header);
        } catch (Throwable ignored) {
        }
    }

    private PushUrl createPushUrl(String rawUrl) {
        PushUrl pushUrl = new PushUrl();
        pushUrl.url = rawUrl == null ? "" : rawUrl;
        return pushUrl;
    }

    private PushUrl parsePushUrl(String rawUrl) {
        PushUrl pushUrl = createPushUrl(rawUrl);
        parseMarkedHeaders(pushUrl);
        return pushUrl;
    }

    private boolean parseMarkedHeaders(PushUrl pushUrl) {
        String marker = PUSH_HEADERS_MARKER;
        int start = pushUrl.url.indexOf(marker);
        if (start < 0) return false;
        int valueStart = start + marker.length();
        int end = pushUrl.url.indexOf('@', valueStart);
        if (end < 0) return false;
        try {
            String text = URLDecoder.decode(pushUrl.url.substring(valueStart, end), "UTF-8");
            JSONObject json = new JSONObject(text);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = json.optString(key, "");
                if (!TextUtils.isEmpty(key)) pushUrl.headers.put(key, value);
            }
            pushUrl.url = pushUrl.url.substring(0, start) + pushUrl.url.substring(end + 1);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static class PushUrl {
        String url = "";
        HashMap<String, String> headers = new HashMap<>();
    }

    private void postPlayResult(int requestSeq, JSONObject result) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (requestSeq != playRequestSeq.get()) {
                    LOG.i("echo--getPlay--ignore stale result");
                    return;
                }
                playResult.setValue(result);
            }
        });
    }

    private static final ConcurrentHashMap<String, String> extendCache = new ConcurrentHashMap<>();

    private String getFixUrl(final String extend) {
        return getFixUrl(extend, 20);
    }

    private String getFixUrl(final String extend, final long timeoutSeconds) {
        if (TextUtils.isEmpty(extend)) return "";
        if(!extend.startsWith("http"))return extend;
        final String key = MD5.string2MD5(extend);
        String cached = extendCache.get(key);
        if (cached != null) {
            LOG.i("echo-getFixUrl Cache");
            return cached;
        }
        // 调用方必须已经离开主线程。这里直接请求，不再丢进播放那条单线程里等待。
        try {
            String result = extend;
            if (extend.startsWith("http://127.0.0.1")) {
                String path = extend.replaceAll("^http.+/file/", FileUtils.getRootPath() + "/");
                path = path.replaceAll("localhost/", "/");
                result = FileUtils.readFileToString(path, "UTF-8");
                if (result == null) {
                    result = "";
                }
                result = tryMinifyJson(result);
                extendCache.putIfAbsent(key, result);
            } else if (extend.startsWith("http")) {
                long timeoutMs = timeoutSeconds > 0 ? timeoutSeconds * 1000L : 20000L;
                result = OkHttp.string(extend, timeoutMs);
                if (result != null && !result.isEmpty()) {
                    result = tryMinifyJson(result);
                    if (result.length() > 2500) {
                        result = extend;
                    }
                    extendCache.putIfAbsent(key, result);
                } else {
                    result = extend;
                }
            }
            return result == null ? extend : result;
        } catch (RuntimeException ex) {
            LOG.e("echo-getFixUrl-failed: " + ex.getMessage());
            return extend;
        }
    }

    private String tryMinifyJson(String raw) {
        try {
            raw = raw.trim();
            JsonElement jsonElement = JsonParser.parseString(raw);
            return gson.toJson(jsonElement);
        } catch (Exception e) {
            return raw;
        }
    }

    private MovieSort.SortFilter getSortFilter(JsonObject obj) {
        String key = obj.get("key").getAsString();
        String name = obj.get("name").getAsString();
        JsonArray kv = obj.getAsJsonArray("value");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (JsonElement ele : kv) {
            JsonObject ele_obj = ele.getAsJsonObject();
            String values_key=ele_obj.has("n")?ele_obj.get("n").getAsString():"";
            String values_value=ele_obj.has("v")?ele_obj.get("v").getAsString():"";
            values.put(values_key, values_value);
        }
        MovieSort.SortFilter filter = new MovieSort.SortFilter();
        filter.key = key;
        filter.name = name;
        filter.values = values;
        return filter;
    }

    private AbsSortXml sortJson(MutableLiveData<AbsSortXml> result, String json) {
        try {
            if (TextUtils.isEmpty(json)) {
                return new AbsSortJson().toAbsSortXml();
            }
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            AbsSortJson sortJson = gson.fromJson(obj, new TypeToken<AbsSortJson>() {
            }.getType());
            AbsSortXml data = sortJson.toAbsSortXml();
            try {
                if (obj.has("filters")) {
                    LinkedHashMap<String, ArrayList<MovieSort.SortFilter>> sortFilters = new LinkedHashMap<>();
                    JsonObject filters = obj.getAsJsonObject("filters");
                    for (String key : filters.keySet()) {
                        ArrayList<MovieSort.SortFilter> sortFilter = new ArrayList<>();
                        JsonElement one = filters.get(key);
                        if (one.isJsonObject()) {
                            sortFilter.add(getSortFilter(one.getAsJsonObject()));
                        } else {
                            for (JsonElement ele : one.getAsJsonArray()) {
                                sortFilter.add(getSortFilter(ele.getAsJsonObject()));
                            }
                        }
                        sortFilters.put(key, sortFilter);
                    }
                    if (data.classes != null && data.classes.sortList != null) {
                        for (MovieSort.SortData sort : data.classes.sortList) {
                            if (sortFilters.containsKey(sort.id) && sortFilters.get(sort.id) != null) {
                                sort.filters = sortFilters.get(sort.id);
                            }
                        }
                    }
                }
            } catch (Throwable th) {

            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private AbsSortXml sortXml(MutableLiveData<AbsSortXml> result, String xml) {
        try {
            XStream xstream = new XStream(new DomDriver());//创建Xstram对象
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsSortXml.class);
            xstream.ignoreUnknownElements();
            AbsSortXml data = (AbsSortXml) xstream.fromXML(xml);
            for (MovieSort.SortData sort : data.classes.sortList) {
                if (sort.filters == null) {
                    sort.filters = new ArrayList<>();
                }
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private void absXml(AbsXml data, String sourceKey) {
        absXml(data, sourceKey, "");
    }

    private void absXml(AbsXml data, String sourceKey, String searchToken) {
        data.sourceKey = sourceKey;
        data.searchToken = searchToken;
        if (data.movie != null && data.movie.videoList != null) {
            for (Movie.Video video : data.movie.videoList) {
                if (video.urlBean != null && video.urlBean.infoList != null) {
                    for (Movie.Video.UrlBean.UrlInfo urlInfo : video.urlBean.infoList) {
                        String[] str = null;
                        if (urlInfo.urls.contains("#")) {
                            str = urlInfo.urls.split("#");
                        } else {
                            str = new String[]{urlInfo.urls};
                        }
                        List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
//                        for (String s : str) {
//                            if (s.contains("$")) {
//                                String[] ss = s.split("\\$");
//                                if (ss.length >= 2) {
//                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
//                                }
//                                //infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(s.substring(0, s.indexOf("$")), s.substring(s.indexOf("$") + 1)));
//                            }
//                        }
                        for (String s : str) {
                            String[] ss = s.split("\\$", 2);
                            if (ss.length > 0) {
                                if (ss.length >= 2) {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                } else {
                                    infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                }
                            }
                        }
                        urlInfo.beanList = infoBeanList;
                    }
                }
                video.sourceKey = sourceKey;
            }
        }
    }
    
    private AbsXml checkPush(AbsXml data) {
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() > 0) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null && video.urlBean.infoList.size() > 0) {
                for (int i = 0; i < video.urlBean.infoList.size(); i++) {
                    Movie.Video.UrlBean.UrlInfo urlinfo = video.urlBean.infoList.get(i);
                    if (urlinfo != null && urlinfo.beanList != null && !urlinfo.beanList.isEmpty()) {
                        for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlinfo.beanList) {
                            if (infoBean.url.startsWith("push://")) {
                                String pushUrl = infoBean.url.substring(7);
                                if (pushUrl.startsWith("b64:")) {
                                    try {
                                        pushUrl = new String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
                                    } catch (UnsupportedEncodingException e) {
                                        e.printStackTrace();
                                    }
                                } else {
                                    pushUrl = URLDecoder.decode(pushUrl);
                                }

                                final AbsXml[] resData = {null};

                                final CountDownLatch countDownLatch = new CountDownLatch(1);
                                ExecutorService threadPool = Executors.newSingleThreadExecutor();
                                String finalPushUrl = pushUrl;
                                threadPool.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        SourceBean sb = ApiConfig.get().getSource("push_agent");
                                        if (sb == null) {
                                            countDownLatch.countDown();
                                            return;
                                        }
                                        if (sb.getType() == 4) {
                                            OkGo.<String>get(sb.getApi())
                                                    .tag("detail")
                                                    .params("ac","detail")
                                                    .params("ids", finalPushUrl)
                                                    .execute(new AbsCallback<String>() {
                                                        @Override
                                                        public String convertResponse(okhttp3.Response response) throws Throwable {
                                                            if (response.body() != null) {
                                                                return response.body().string();
                                                            } else {
                                                                return "";
                                                            }
                                                        }

                                                        @Override
                                                        public void onSuccess(Response<String> response) {
                                                            String res = response.body();
                                                            if (!TextUtils.isEmpty(res)) {
                                                                try {
                                                                    AbsJson absJson = gson.fromJson(res, new TypeToken<AbsJson>() {
                                                                    }.getType());
                                                                    resData[0] = absJson.toAbsXml();
                                                                    absXml(resData[0], sb.getKey());
                                                                } catch (Exception e) {
                                                                    e.printStackTrace();
                                                                }
                                                            }
                                                            countDownLatch.countDown();
                                                        }

                                                        @Override
                                                        public void onError(Response<String> response) {
                                                            super.onError(response);
                                                            countDownLatch.countDown();
                                                        }
                                                    });
                                        } else {
                                            try {
                                                Spider sp = ApiConfig.get().getCSP(sb);
                                             //   ApiConfig.get().setPlayJarKey(sb.getJar());
                                                List<String> ids = new ArrayList<>();
                                                ids.add(finalPushUrl);
                                                String res = sp.detailContent(ids);
                                                if (!TextUtils.isEmpty(res)) {
                                                    try {
                                                        AbsJson absJson = gson.fromJson(res, new TypeToken<AbsJson>() {}.getType());
                                                        resData[0] = absJson.toAbsXml();
                                                        absXml(resData[0], sb.getKey());
                                                    } catch (Exception e) {
                                                        e.printStackTrace();
                                                    }
                                                }
                                            } catch (Throwable th) {
                                                th.printStackTrace();
                                            }
                                            countDownLatch.countDown();
                                        }
                                    }
                                });
                                try {
                                    countDownLatch.await(15, TimeUnit.SECONDS);
                                    threadPool.shutdown();
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                                if (resData[0] != null) {
                                    AbsXml res = resData[0];
                                    if (res.movie != null && res.movie.videoList != null && res.movie.videoList.size() > 0) {
                                        Movie.Video resVideo = res.movie.videoList.get(0);
                                        if (resVideo != null && resVideo.urlBean != null && resVideo.urlBean.infoList != null && resVideo.urlBean.infoList.size() > 0) {
                                            if (urlinfo.beanList.size() == 1) {
                                                video.urlBean.infoList.remove(i);
                                            } else {
                                                urlinfo.beanList.remove(infoBean);
                                            }
                                            for (Movie.Video.UrlBean.UrlInfo resUrlinfo : resVideo.urlBean.infoList) {
                                                if (resUrlinfo != null && resUrlinfo.beanList != null && !resUrlinfo.beanList.isEmpty()) {
                                                    video.urlBean.infoList.add(resUrlinfo);
                                                }
                                            }
                                            video.sourceKey = "push_agent";
                                            return data;
                                        }
                                    }
                                }
                                infoBean.name = "解析失败 >>> " + infoBean.name;
                            }
                        }
                    }
                }
            }
        }
        return data;
    }

    public void checkThunder(AbsXml data, int index) {
        boolean thunderParse = false;
        if (data.movie != null && data.movie.videoList != null && data.movie.videoList.size() == 1) {
            Movie.Video video = data.movie.videoList.get(0);
            if (video != null && video.urlBean != null && video.urlBean.infoList != null) {
                boolean hasThunder=false;
                thunderLoop:
                for (int idx=0;idx<video.urlBean.infoList.size();idx++) {
                    Movie.Video.UrlBean.UrlInfo urlInfo = video.urlBean.infoList.get(idx);
                    for (Movie.Video.UrlBean.UrlInfo.InfoBean infoBean : urlInfo.beanList) {
                        if(Thunder.isSupportUrl(infoBean.url)){
                            hasThunder=true;
                            break thunderLoop;
                        }
                    }
                }
                if (hasThunder) {
                    thunderParse = true;
                    Thunder.parse(App.getInstance(), video.urlBean, new Thunder.ThunderCallback() {
                        @Override
                        public void status(int code, String info) {
                            if (code >= 0) {
                                LOG.i(info);
                            } else {
                                video.urlBean.infoList.get(0).beanList.get(0).name = info;
                                detailResult.postValue(data);
                            }
                        }

                        @Override
                        public void list(Map<Integer, String> urlMap) {
                            for (int key : urlMap.keySet()) {
                                String playList=urlMap.get(key);
                                video.urlBean.infoList.get(key).urls = playList;
                                String[] str = playList.split("#");
                                List<Movie.Video.UrlBean.UrlInfo.InfoBean> infoBeanList = new ArrayList<>();
                                for (String s : str) {
                                    if (s.contains("$")) {
                                        String[] ss = s.split("\\$", 2);

                                        if (ss.length > 0) {
                                            if (ss.length >= 2) {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]));
                                            } else {
                                                infoBeanList.add(new Movie.Video.UrlBean.UrlInfo.InfoBean((infoBeanList.size() + 1) + "", ss[0]));
                                            }
                                        }
                                    }
                                }
                                video.urlBean.infoList.get(key).beanList = infoBeanList;
                            }
                            detailResult.postValue(data);
                        }

                        @Override
                        public void play(String url) {

                        }
                    });
                }
            }
        }
        if (!thunderParse && index==0) {
            detailResult.postValue(data);
        }
    }

    private AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey) {
        return xml(result, xml, sourceKey, "");
    }

    private AbsXml xml(MutableLiveData<AbsXml> result, String xml, String sourceKey, String searchToken) {
        try {
            XStream xstream = new XStream(new DomDriver());//创建Xstram对象
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsXml.class);
            xstream.ignoreUnknownElements();
            if (xml.contains("<year></year>")) {
                xml = xml.replace("<year></year>", "<year>0</year>");
            }
            if (xml.contains("<state></state>")) {
                xml = xml.replace("<state></state>", "<state>0</state>");
            }
            AbsXml data = (AbsXml) xstream.fromXML(xml);
            absXml(data, sourceKey, searchToken);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                	data = checkPush(data);
                    checkThunder(data,0);
                }else {
                    postSearchResult(result, data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result || quickSearchResult == result || detailFallbackSearchResult == result) {
                postEmptySearchResult(result, sourceKey, searchToken);
            } else if (result != null) {
                if (result == detailResult) {
                    AbsXml data = new AbsXml();
                    data.sourceKey = sourceKey;
                    result.postValue(data);
                } else {
                    result.postValue(null);
                }
            }
            return null;
        }
    }

    private AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey) {
        return json(result, json, sourceKey, "");
    }

    private AbsXml json(MutableLiveData<AbsXml> result, String json, String sourceKey, String searchToken) {
        try {
            // 测试数据
//            json = "{\n" +
//                    "\t\"list\": [{\n" +
//                    "\t\t\"vod_id\": \"137133\",\n" +
//                    "\t\t\"vod_name\": \"磁力测试\",\n" +
//                    "\t\t\"vod_pic\": \"https:/img9.doubanio.com/view/photo/s_ratio_poster/public/p2656327176.webp\",\n" +
//                    "\t\t\"type_name\": \"剧情 / 爱情 / 古装\",\n" +
//                    "\t\t\"vod_year\": \"2022\",\n" +
//                    "\t\t\"vod_area\": \"中国大陆\",\n" +
//                    "\t\t\"vod_remarks\": \"40集全\",\n" +
//                    "\t\t\"vod_actor\": \"刘亦菲\",\n" +
//                    "\t\t\"vod_director\": \"杨阳\",\n" +
//                    "\t\t\"vod_content\": \"　　在钱塘开茶铺的赵盼儿（刘亦菲 饰）惊闻未婚夫、新科探花欧阳旭（徐海乔 饰）要另娶当朝高官之女，不甘命运的她誓要上京讨个公道。在途中她遇到了出自权门但生性正直的皇城司指挥顾千帆（陈晓 饰），并卷入江南一场大案，两人不打不相识从而结缘。赵盼儿凭借智慧解救了被骗婚而惨遭虐待的“江南第一琵琶高手”宋引章（林允 饰）与被苛刻家人逼得离家出走的豪爽厨娘孙三娘（柳岩 饰），三位姐妹从此结伴同行，终抵汴京，见识世间繁华。为了不被另攀高枝的欧阳旭从东京赶走，赵盼儿与宋引章、孙三娘一起历经艰辛，将小小茶坊一步步发展为汴京最大的酒楼，揭露了负心人的真面目，收获了各自的真挚感情和人生感悟，也为无数平凡女子推开了一扇平等救赎之门。\",\n" +
//                    "\t\t\"vod_play_from\": \"磁力测试\",\n" +
//                    "\t\t\"vod_play_url\": \"0$magnet:?xt=urn:btih:e398ca38fb9d64897ed19b4d16efeea11af4d03b\"\n" +
//                    "\t}]\n" +
//                    "}";
            AbsJson absJson = gson.fromJson(json, new TypeToken<AbsJson>() {
            }.getType());
            AbsXml data = absJson.toAbsXml();
            absXml(data, sourceKey, searchToken);
            if (searchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
            } else if (quickSearchResult == result) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
            } else if (result != null) {
                if (result == detailResult) {
                	data = checkPush(data);
                    checkThunder(data,0);
                }else {
                    postSearchResult(result, data);
                }
            }
            return data;
        } catch (Exception e) {
            if (searchResult == result || quickSearchResult == result || detailFallbackSearchResult == result) {
                postEmptySearchResult(result, sourceKey, searchToken);
            } else if (result != null) {
                if (result == detailResult) {
                    AbsXml data = new AbsXml();
                    data.sourceKey = sourceKey;
                    result.postValue(data);
                } else {
                    result.postValue(null);
                }
            }
            return null;
        }
    }

    private String getFixUrlDirect(final String extend) {
        return getFixUrl(extend, 30);
    }

    private void postEmptySearchResult(MutableLiveData<AbsXml> result, String sourceKey) {
        postEmptySearchResult(result, sourceKey, "");
    }

    private void postEmptySearchResult(MutableLiveData<AbsXml> result, String sourceKey, String searchToken) {
        AbsXml data = new AbsXml();
        data.sourceKey = sourceKey;
        data.searchToken = searchToken;
        if (searchResult == result) {
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, data));
        } else if (quickSearchResult == result) {
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_RESULT, data));
        } else if (result != null) {
            postSearchResult(result, data);
        }
    }

    private void postSearchResult(final MutableLiveData<AbsXml> result, final AbsXml data) {
        if (result == detailFallbackSearchResult) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    result.setValue(data);
                }
            });
        } else {
            result.postValue(data);
        }
    }

    @Override
    protected void onCleared() {
        cancelPlayRequest();
        cancelDetailJob();
        OkGo.getInstance().cancelTag("detail");
        super.onCleared();
    }
}
