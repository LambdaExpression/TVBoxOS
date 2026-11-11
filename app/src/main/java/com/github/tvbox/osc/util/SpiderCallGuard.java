package com.github.tvbox.osc.util;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.Response;

/**
 * 按爬虫调用线程标记 OkHttp 请求，只取消这一轮，不影响图片、配置等其它请求。
 * JAR 爬虫走共用客户端且没有 tag，只能在发起线程上登记 Call。
 * JAR 里自己 new OkHttpClient() 的请求不经过这里，取消不到。
 */
public final class SpiderCallGuard {

    /** 0 表示当前线程不在爬虫调用里。子线程继承，便于爬虫自己再开线程发请求。 */
    private static final InheritableThreadLocal<Long> SCOPE = new InheritableThreadLocal<>();
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final ConcurrentHashMap<Long, Set<Call>> CALLS = new ConcurrentHashMap<>();
    private static final Interceptor INTERCEPTOR = new Interceptor() {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Long scopeId = SCOPE.get();
            if (scopeId == null) {
                return chain.proceed(chain.request());
            }
            Set<Call> calls = CALLS.get(scopeId);
            Call call = chain.call();
            if (calls == null) {
                call.cancel();
                throw new IOException("spider request cancelled, scope=" + scopeId);
            }
            calls.add(call);
            try {
                return chain.proceed(chain.request());
            } finally {
                calls.remove(call);
            }
        }
    };

    private SpiderCallGuard() {
    }

    /**
     * 挂到共用 OkHttpClient 上。必须在客户端创建时加入，否则 JAR 里的请求登记不到。
     *
     * @return 无状态拦截器，可以给多个 client 共用
     */
    public static Interceptor interceptor() {
        return INTERCEPTOR;
    }

    /**
     * 标记当前线程及其后创建的子线程。必须在爬虫方法调用前执行。
     *
     * @return 本轮编号，取消和结束都用这个编号
     */
    public static long open() {
        long scopeId = NEXT_ID.incrementAndGet();
        CALLS.put(scopeId, ConcurrentHashMap.newKeySet());
        SCOPE.set(scopeId);
        return scopeId;
    }

    /**
     * 取消这一轮已经发出和随后再发出的请求。重复调用是安全的。
     *
     * @param scopeId {@link #open()} 的返回值，0 表示没有可取消的一轮
     */
    public static void cancel(long scopeId) {
        if (scopeId == 0) {
            return;
        }
        Set<Call> calls = CALLS.remove(scopeId);
        if (calls == null) {
            return;
        }
        for (Call call : calls) {
            try {
                call.cancel();
            } catch (RuntimeException ex) {
                LOG.e("echo-spider-call-cancel: " + ex.getMessage());
            }
        }
    }

    /**
     * 正常结束时卸下标记，不取消连接。
     *
     * @param scopeId {@link #open()} 的返回值
     */
    public static void close(long scopeId) {
        if (scopeId == 0) {
            return;
        }
        CALLS.remove(scopeId);
        Long current = SCOPE.get();
        if (current != null && current.longValue() == scopeId) {
            SCOPE.remove();
        }
    }
}
