package com.github.catvod.crawler;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.net.OkHttp;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.MD5;
import com.lzy.okgo.OkGo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import dalvik.system.DexClassLoader;
import okhttp3.Response;

/**
 * 把订阅里的爬虫包装进独立的 DexClassLoader，再按站点创建爬虫实例。
 * 主包更新时必须换加载器：缓存文件会被下载逻辑删掉换新，继续用旧加载器的话，搜索和详情会整批变成空实现。
 */
public class JarLoader {

    private static final String TAG = "JarLoader";
    private static final String MAIN_KEY = "main";

    private final ConcurrentHashMap<String, DexClassLoader> loaders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> proxyMethods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> danmuClickMethods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> danmuLongClickMethods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Spider> spiders = new ConcurrentHashMap<>();
    /** 实例是用哪一份加载器创建的。换包时只丢掉旧加载器上的实例。 */
    private final ConcurrentHashMap<String, DexClassLoader> spiderLoaders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> siteJarKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> aliases = new ConcurrentHashMap<>();
    private final ProtectedInitJar protectedInitJar = new ProtectedInitJar();
    private volatile String recent = MAIN_KEY;
    /** 主包当前映射的副本。下一轮替换时保留它，正在执行的调用还指着这份文件。 */
    private volatile File mountedMain;
    /** 主包加载代次。清空或新一轮加载之后，还在跑的旧加载不能再写回。 */
    private final AtomicInteger mainLoadEpoch = new AtomicInteger();

    /**
     * 加载订阅下发的主爬虫包。每次都从缓存拷一份再挂载，避免下载端删掉原文件后把正在用的加载器拆掉。
     * 已经挂过主包时会换成新加载器，并卸掉旧实例，下次搜索和分类会用新包重新创建。
     *
     * @param cache 缓存 jar 的绝对路径
     * @return 新加载器已经初始化完成
     */
    public boolean load(String cache) {
        File source = new File(cache);
        if (!exists(source)) return false;
        File copy = new File(jarDir(), MAIN_KEY + "-" + System.currentTimeMillis() + ".jar");
        int epoch = mainLoadEpoch.incrementAndGet();
        try {
            copyJar(source, copy);
        } catch (IOException e) {
            Log.i(TAG, "echo-jar-copy-failed " + e.getMessage());
            deleteQuietly(copy);
            return false;
        }
        boolean success = load(MAIN_KEY, copy, true, epoch);
        if (!success) {
            deleteQuietly(copy);
            return false;
        }
        recent = MAIN_KEY;
        File previous = mountedMain;
        mountedMain = copy;
        deleteOldMainCopies(copy, previous);
        return true;
    }

    public void setRecentJarKey(String key) {
        if (TextUtils.isEmpty(key)) return;
        recent = realKey(key);
        injectProxyPort(loaders.get(recent));
    }

    public void loadLiveJar(String jar) {
        String key = jarKey(jar);
        parseJar(key, jar);
        setRecentJarKey(key);
    }

    /**
     * 只丢掉已经创建的爬虫实例，已加载的 dex 留着。
     * 配置更新但爬虫包地址没变时用，避免栏目请求找不到类，全部变成没数据。
     */
    public void clearSpiders() {
        for (Spider spider : spiders.values()) {
            destroyQuietly(spider);
        }
        spiders.clear();
        spiderLoaders.clear();
        siteJarKeys.clear();
    }

    public void clear() {
        synchronized (lock(MAIN_KEY)) {
            // 先作废进行中的主包加载，再清地图，避免旧加载在清空之后把加载器写回来。
            mainLoadEpoch.incrementAndGet();
            clearSpiders();
            loaders.clear();
            proxyMethods.clear();
            danmuClickMethods.clear();
            danmuLongClickMethods.clear();
            spiders.clear();
            spiderLoaders.clear();
            locks.clear();
            siteJarKeys.clear();
            aliases.clear();
            protectedInitJar.clear();
            mountedMain = null;
            recent = MAIN_KEY;
        }
    }

    /**
     * 站点单独指定的 jar 已在内存里就继续用，不跟着主包每次更新重挂。
     */
    private boolean load(String key, File file) {
        return load(key, file, false, 0);
    }

    /**
     * @param replace true 时新加载器初始化成功后才换上，并卸掉这个 key 上的旧实例
     * @param epoch   主包代次，0 表示站点包，不参与主包作废判断
     * @return 加载器可用。失败或这一轮已经过期时，原来的加载器保持不动
     */
    private boolean load(String key, File file, boolean replace, int epoch) {
        if (Thread.interrupted()) return false;
        if (!exists(file)) return false;
        synchronized (lock(key)) {
            if (isStaleMainLoad(replace, epoch)) return false;
            if (!replace && loaders.containsKey(key)) return true;
        }
        DexClassLoader loader;
        Method proxy;
        Method click;
        Method longClick;
        try {
            if (!file.setReadOnly()) {
                Log.i(TAG, "echo-jar-readonly-failed " + file.getAbsolutePath());
            }
            String cachePath = jarDir().getAbsolutePath();
            loader = new DexClassLoader(file.getAbsolutePath(), cachePath, cachePath, App.getInstance().getClassLoader());
            if (!invokeInit(loader, file.getAbsolutePath())) {
                Log.i(TAG, "load error key=" + key + ", protected jar loader not bound");
                return false;
            }
            proxy = findProxy(loader);
            click = findDanmaku(loader, "onClick");
            longClick = findDanmaku(loader, "onLongClick");
        } catch (Throwable e) {
            Log.i(TAG, "load error key=" + key + ", msg=" + e.getMessage());
            e.printStackTrace();
            return false;
        }
        synchronized (lock(key)) {
            if (isStaleMainLoad(replace, epoch)) return false;
            if (!replace && loaders.containsKey(key)) return true;
            loaders.put(key, loader);
            if (proxy != null) proxyMethods.put(key, proxy);
            else proxyMethods.remove(key);
            if (click != null) danmuClickMethods.put(key, click);
            else danmuClickMethods.remove(key);
            if (longClick != null) danmuLongClickMethods.put(key, longClick);
            else danmuLongClickMethods.remove(key);
            injectProxyPort(loader);
            if (replace) retireSpiders(key, loader);
            Log.i(TAG, "load success key=" + key + ", file=" + file.getAbsolutePath());
            return true;
        }
    }

    /** 主包这一轮已经被更新的加载或 clear 盖掉了。 */
    private boolean isStaleMainLoad(boolean replace, int epoch) {
        if (!replace || epoch == mainLoadEpoch.get()) return false;
        Log.i(TAG, "echo-jar-load-stale epoch=" + epoch);
        return true;
    }

    /**
     * 新加载器已经放进 map 之后，只卸掉仍绑在旧加载器上的实例。
     * 并发创建的爬虫如果已经用上新加载器，就留着。
     */
    private void retireSpiders(String jaKey, DexClassLoader current) {
        ArrayList<String> siteKeys = new ArrayList<>();
        for (Map.Entry<String, String> entry : siteJarKeys.entrySet()) {
            if (jaKey.equals(entry.getValue())) siteKeys.add(entry.getKey());
        }
        for (String siteKey : siteKeys) {
            if (siteKey == null) continue;
            String spKey = jaKey + siteKey;
            synchronized (lock("spider:" + spKey)) {
                DexClassLoader used = spiderLoaders.get(spKey);
                if (used == current) continue;
                destroyQuietly(spiders.remove(spKey));
                spiderLoaders.remove(spKey);
                if (jaKey.equals(siteJarKeys.get(siteKey))) siteJarKeys.remove(siteKey);
            }
        }
    }

    /** 实例还绑在当前加载器上才复用。换包后的旧实例不能再拿去搜索。 */
    private boolean sameLoader(String spKey, String jaKey) {
        DexClassLoader used = spiderLoaders.get(spKey);
        DexClassLoader current = loaders.get(jaKey);
        return used != null && used == current;
    }

    /** 用指定加载器新建站点爬虫。失败把异常抛给调用方，避免把空实例写进缓存。 */
    private Spider newSpider(DexClassLoader loader, String key, String api, String ext) throws Throwable {
        Spider spider = (Spider) loader.loadClass("com.github.catvod.spider." + className(api)).newInstance();
        spider.siteKey = key;
        spider.initApi(new SpiderApi());
        spider.init(App.getInstance(), ext);
        return spider;
    }

    /** 换包或清缓存时停掉这个实例上还在飞的请求。基类销毁是空的，子类覆写了才有额外动作。 */
    private void destroyQuietly(Spider spider) {
        if (spider == null) return;
        try {
            spider.cancelByTag();
        } catch (Throwable ignored) {
        }
        try {
            spider.destroy();
        } catch (Throwable ignored) {
        }
    }

    /** 新包里没有 Proxy 时返回 null，调用方会清掉上一次的方法。 */
    private Method findProxy(DexClassLoader loader) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Proxy");
            return clz.getMethod("proxy", Map.class);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 弹幕点击方法，类或方法不存在就当这个包不支持。 */
    private Method findDanmaku(DexClassLoader loader, String name) {
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Danmaku");
            return clz.getMethod(name, String.class, String.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 拷到独立文件再交给 ClassLoader。调用方随后可以删掉或覆盖源文件。
     */
    static void copyJar(File source, File dest) throws IOException {
        if (source == null || dest == null) throw new IOException("jar path is null");
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("mkdir failed: " + parent.getAbsolutePath());
        }
        if (dest.exists() && !dest.delete()) {
            throw new IOException("delete failed: " + dest.getAbsolutePath());
        }
        InputStream input = null;
        FileOutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = new FileOutputStream(dest);
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        } finally {
            close(input);
            close(output);
        }
        if (!dest.isFile() || dest.length() <= 0) {
            deleteQuietly(dest);
            throw new IOException("empty jar copy: " + dest.getAbsolutePath());
        }
    }

    /** 只删更早的主包副本，当前这份和上一份留着，避免正在跑的调用读到已删除的文件。 */
    private void deleteOldMainCopies(File current, File previous) {
        File[] files = jarDir().listFiles();
        if (files == null) return;
        String keepCurrent = current == null ? "" : current.getName();
        String keepPrevious = previous == null ? "" : previous.getName();
        for (File file : files) {
            if (file == null) continue;
            String name = file.getName();
            if (name == null || !name.startsWith(MAIN_KEY + "-") || !name.endsWith(".jar")) continue;
            if (name.equals(keepCurrent) || name.equals(keepPrevious)) continue;
            deleteQuietly(file);
        }
    }

    /** 删失败只记日志，不让清理影响这次挂载结果。 */
    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.i(TAG, "echo-jar-delete-failed " + file.getAbsolutePath());
        }
    }

    private boolean invokeInit(DexClassLoader loader, String jar) {
        boolean protectedJar = false;
        try {
            Class<?> clz = loader.loadClass("com.github.catvod.spider.Init");
            protectedJar = protectedInitJar.check(jar);
            if (protectedJar) {
                Log.i(TAG, "echo-load initProtectedJar file=" + jar);
                return protectedInitJar.init(clz);
            } else {
                Method method = clz.getMethod("init", Context.class);
                method.invoke(null, App.getInstance());
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return !protectedJar;
    }

    public void parseJar(String key, String jar) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(jar)) return;
        if (loaders.containsKey(key)) return;
        Object lock = lock(key);
        synchronized (lock) {
            if (loaders.containsKey(key)) return;
            String source = jar;
            String md5 = "";
            String[] texts = jar.split(";md5;");
            if (texts.length > 1) {
                source = texts[0];
                md5 = texts[1].trim();
            }
            aliases.put(jarKey(source), key);
            if (md5.startsWith("http")) {
                String value = OkHttp.string(md5, null);
                md5 = value == null ? "" : value.trim();
            }
            File file = fileForJar(source);
            if (!TextUtils.isEmpty(md5) && exists(file) && MD5.getFileMd5(file).equalsIgnoreCase(md5)) {
                load(key, file);
            } else if (TextUtils.isEmpty(md5) && exists(file) && !FileUtils.isWeekAgo(file)) {
                load(key, file);
            } else if (source.startsWith("http")) {
                load(key, download(source, file));
            } else if (source.startsWith("assets")) {
                load(key, copyAsset(source, file));
            } else if (source.startsWith("file")) {
                load(key, local(source));
            } else if (source.startsWith("clan://")) {
                load(key, download(clanToAddress(source), file));
            }
        }
    }

    public DexClassLoader dex(String jar) {
        try {
            String key = jarKey(jar);
            parseJar(key, jar);
            return loaders.get(key);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        key = key == null ? "" : key;
        api = api == null ? "" : api;
        ext = ext == null ? "" : ext;
        jar = jar == null ? "" : jar;
        if (TextUtils.isEmpty(api)) return new SpiderNull();

        String jaKey = TextUtils.isEmpty(jar) ? MAIN_KEY : jarKey(jar);
        String spKey = jaKey + key;
        recent = jaKey;
        siteJarKeys.put(key, jaKey);
        injectProxyPort(loaders.get(jaKey));

        Spider cached = spiders.get(spKey);
        if (cached != null && sameLoader(spKey, jaKey)) {
            Log.i(TAG, "echo-getSpider cached key=" + spKey);
            return cached;
        }

        synchronized (lock("spider:" + spKey)) {
            cached = spiders.get(spKey);
            if (cached != null && sameLoader(spKey, jaKey)) return cached;
            if (cached != null) {
                destroyQuietly(spiders.remove(spKey));
                spiderLoaders.remove(spKey);
            }
            try {
                if (!MAIN_KEY.equals(jaKey)) parseJar(jaKey, jar);
                DexClassLoader loader = loaders.get(jaKey);
                if (loader == null) return new SpiderNull();
                Spider spider = newSpider(loader, key, api, ext);
                DexClassLoader current = loaders.get(jaKey);
                // 创建过程中主包被换掉时，丢掉刚建的旧实例，改用新加载器再来一次。
                if (current != loader) {
                    destroyQuietly(spider);
                    loader = current;
                    if (loader == null) return new SpiderNull();
                    spider = newSpider(loader, key, api, ext);
                }
                spiders.put(spKey, spider);
                spiderLoaders.put(spKey, loader);
                Log.i(TAG, "echo-getSpider success key=" + spKey);
                return spider;
            } catch (Throwable e) {
                Log.i(TAG, "echo-getSpider error key=" + spKey + ", msg=" + e.getMessage());
                e.printStackTrace();
                return new SpiderNull();
            }
        }
    }

    public void searchDanmuUi(String name, String episode, boolean longClick) {
        try {
            ConcurrentHashMap<String, Method> methods = longClick ? danmuLongClickMethods : danmuClickMethods;
            Method method = methods.get(recent);
            if (method == null) method = methods.get("main");
            if (method == null) return;
            method.invoke(null, name, episode);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    public boolean hasDanmuSearchUi() {
        return danmuClickMethods.containsKey(recent) || danmuLongClickMethods.containsKey(recent);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) {
        try {
            Class<?> clz = loadParserClass("com.github.catvod.parser.Json" + key);
            Method method = clz.getMethod("parse", LinkedHashMap.class, String.class);
            return (JSONObject) method.invoke(null, jxs, url);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) {
        try {
            Class<?> clz = loadParserClass("com.github.catvod.parser.Mix" + key);
            Method method = clz.getMethod("parse", LinkedHashMap.class, String.class, String.class, String.class);
            return (JSONObject) method.invoke(null, jxs, name, flag, url);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    public Object[] proxyInvoke(Map<String, String> params) {
        String siteKey = params == null ? null : params.get("siteKey");
        if (!TextUtils.isEmpty(siteKey)) {
            Object[] result = proxyInvoke(proxyMethods.get(siteJarKeys.get(siteKey)), params);
            if (result != null) return result;
        }
        Object[] result = proxyInvoke(proxyMethods.get(recent), params);
        if (result != null) return result;
        for (Map.Entry<String, Method> entry : proxyMethods.entrySet()) {
            if (entry.getKey().equals(recent)) continue;
            result = proxyInvoke(entry.getValue(), params);
            if (result != null) return result;
        }
        return null;
    }

    private Object[] proxyInvoke(Method method, Map<String, String> params) {
        try {
            return method == null ? null : (Object[]) method.invoke(null, params);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    private DexClassLoader requireRecentLoader() {
        DexClassLoader loader = loaders.get(recent);
        if (loader == null) loader = loaders.get(MAIN_KEY);
        if (loader == null) throw new IllegalStateException("No jar loaded for recent key: " + recent);
        return loader;
    }

    private Class<?> loadParserClass(String name) throws ClassNotFoundException {
        DexClassLoader loader = loaders.get(recent);
        if (loader != null) {
            try {
                return loader.loadClass(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        loader = loaders.get(MAIN_KEY);
        if (loader != null) return loader.loadClass(name);
        throw new ClassNotFoundException(name);
    }

    private File download(String url, File file) {
        InputStream is = null;
        FileOutputStream os = null;
        try {
            Response response = OkGo.<File>get(url).execute();
            if (response.body() == null) return file;
            is = response.body().byteStream();
            os = new FileOutputStream(create(file));
            byte[] buffer = new byte[16384];
            int length;
            while ((length = is.read(buffer)) != -1) {
                if (Thread.interrupted()) return file;
                os.write(buffer, 0, length);
            }
            os.flush();
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            close(is);
            close(os);
        }
        return file;
    }

    private File copyAsset(String url, File file) {
        InputStream is = null;
        FileOutputStream os = null;
        try {
            String path = url.replace("assets://", "").replace("assets/", "");
            is = App.getInstance().getAssets().open(path);
            os = new FileOutputStream(create(file));
            byte[] buffer = new byte[16384];
            int length;
            while ((length = is.read(buffer)) != -1) {
                os.write(buffer, 0, length);
            }
            os.flush();
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            close(is);
            close(os);
        }
        return file;
    }

    private File local(String path) {
        path = path.replace("file:/", "");
        File file = new File(Environment.getExternalStorageDirectory(), path);
        return file.exists() ? file : new File(path);
    }

    private File fileForJar(String jar) {
        return new File(jarDir(), jarKey(jar) + ".jar");
    }

    private File jarDir() {
        File dir = new File(App.getInstance().getCacheDir(), "jar");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private File create(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (file.exists()) file.delete();
        file.createNewFile();
        file.setReadable(true);
        file.setWritable(true);
        file.setExecutable(true);
        return file;
    }

    private boolean exists(File file) {
        return file != null && file.exists() && file.length() > 0;
    }

    private Object lock(String key) {
        Object lock = locks.get(key);
        if (lock != null) return lock;
        Object created = new Object();
        Object old = locks.putIfAbsent(key, created);
        return old == null ? created : old;
    }

    private String jarKey(String jar) {
        String key = MD5.string2MD5(jar == null ? "" : jar);
        return TextUtils.isEmpty(key) ? MAIN_KEY : key;
    }

    private String realKey(String key) {
        String alias = aliases.get(key);
        return TextUtils.isEmpty(alias) ? key : alias;
    }

    private String className(String api) {
        return api.contains("csp_") ? api.split("csp_")[1] : api;
    }

    private String clanToAddress(String url) {
        if (url.startsWith("clan://localhost/")) {
            return url.replace("clan://localhost/", ControlManager.get().getAddress(true) + "file/");
        }
        if (url.startsWith("clan://")) {
            String text = url.substring(7);
            int index = text.indexOf('/');
            if (index > 0) return "http://" + text.substring(0, index) + "/file/" + text.substring(index + 1);
        }
        return url;
    }

    private void injectProxyPort(DexClassLoader loader) {
        com.github.catvod.Proxy.set(getServerPort());
        if (loader == null) return;
        try {
            Class<?> proxy = loader.loadClass("com.github.catvod.Proxy");
            Method set = proxy.getMethod("set", int.class);
            set.invoke(null, getServerPort());
        } catch (Throwable ignored) {
        }
    }

    private int getServerPort() {
        try {
            String address = ControlManager.get().getAddress(true);
            if (address != null && address.startsWith("http://127.0.0.1:")) {
                String baseUrl = address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
                return Integer.parseInt(baseUrl.substring(baseUrl.lastIndexOf(":") + 1));
            }
        } catch (Throwable ignored) {
        }
        return RemoteServer.serverPort;
    }

    private static void close(java.io.Closeable closeable) {
        try {
            if (closeable != null) closeable.close();
        } catch (Throwable ignored) {
        }
    }
}
