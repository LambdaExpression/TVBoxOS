package com.github.tvbox.osc.util;

import com.github.catvod.crawler.JsLoader;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 跑一轮爬虫调用，并在切源、超时或离开页面时把这轮的线程和 HTTP 一起停掉。
 * 不能只 interrupt：OkHttp 的阻塞读不会因为中断返回，必须先 cancel Call。
 */
public final class SpiderJob {

    /**
     * 实际爬虫调用。在工作线程执行。
     */
    public interface Task {
        /**
         * @return 爬虫返回的 JSON 或 XML，允许空串
         * @throws Exception 网络失败或爬虫内部错误
         */
        String call() throws Exception;
    }

    /**
     * 调用结果。被新一轮替换时不会回调。
     */
    public interface Callback {
        /**
         * @param value 爬虫返回值，可能为空串
         */
        void onResult(String value);

        /** 超过等待时间，请求已取消。 */
        void onTimeout();

        /**
         * @param error 爬虫或线程异常，不含主动取消
         */
        void onError(Throwable error);
    }

    /** 新一轮开始后，旧任务即使还没进爬虫也不再发请求。 */
    private final AtomicBoolean cancelled = new AtomicBoolean();
    /** {@link SpiderCallGuard#open()} 的编号，0 表示还没开始或已经结束。 */
    private volatile long scope;
    /** 爬虫所在线程的 Future，用来解除外层等待。 */
    private volatile Future<?> future;
    /** 单独的线程，避免和单线程池互相卡死；取消时 shutdownNow。 */
    private volatile ExecutorService executor;

    /**
     * 停掉这一轮。已经结束的任务不会去取消其它 JS 请求。
     */
    public void cancel() {
        cancelled.set(true);
        long scopeId = scope;
        if (scopeId != 0) {
            SpiderCallGuard.cancel(scopeId);
        }
        Future<?> current = future;
        boolean running = current != null && !current.isDone();
        if (current != null) {
            current.cancel(true);
        }
        ExecutorService currentExecutor = executor;
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
        }
        if (!running) {
            return;
        }
        try {
            JsLoader.stopAll();
        } catch (RuntimeException ex) {
            LOG.e("echo-spider-job-cancel-js: " + ex.getMessage());
        }
    }

    /**
     * 阻塞直到爬虫返回、超时或被 {@link #cancel()}。应放在后台线程调用。
     *
     * @param timeoutSeconds 等待上限，到点后取消请求并回调 {@link Callback#onTimeout()}
     * @param task           爬虫调用
     * @param callback       结果回调，取消时不调用
     */
    public void run(long timeoutSeconds, Task task, Callback callback) {
        ExecutorService worker = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "tvbox-spider");
                thread.setDaemon(true);
                return thread;
            }
        });
        executor = worker;
        Future<String> result = worker.submit(new java.util.concurrent.Callable<String>() {
            @Override
            public String call() throws Exception {
                if (cancelled.get()) {
                    return "";
                }
                long scopeId = SpiderCallGuard.open();
                scope = scopeId;
                if (cancelled.get()) {
                    SpiderCallGuard.cancel(scopeId);
                    return "";
                }
                try {
                    return task.call();
                } finally {
                    SpiderCallGuard.close(scopeId);
                }
            }
        });
        future = result;
        try {
            String value = result.get(timeoutSeconds, TimeUnit.SECONDS);
            // 被新一轮替换时不再回调，避免旧结果盖住新请求的 loading。
            if (!cancelled.get()) {
                callback.onResult(value);
            }
        } catch (TimeoutException ex) {
            LOG.i("echo-spider-job-timeout");
            cancel();
            callback.onTimeout();
        } catch (CancellationException ex) {
            // 也要回调：ViewPager 多 Fragment 共用静态 SpiderJob 时，旧页取消后若不通知会一直转圈。
            // 调用方用 requestSeq 丢弃过期结果。
            LOG.i("echo-spider-job-cancelled");
            callback.onError(ex);
        } catch (Exception ex) {
            // 爬虫抛错时，即便随后被 cancel，也要把错误交给回调。
            // 调用方用 requestSeq 丢掉过期结果；否则「失败 + 被取消」会一直转圈。
            Throwable error = unwrapExecution(ex);
            if (cancelled.get()) {
                LOG.i("echo-spider-job-cancelled: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
            }
            callback.onError(error);
        } finally {
            worker.shutdownNow();
        }
    }

    /**
     * Future.get 失败时常见包装是 ExecutionException，UI/日志需要真正的爬虫异常。
     *
     * @param error get() 抛出的异常
     * @return 解包后的 cause；没有 cause 则返回原对象
     */
    private static Throwable unwrapExecution(Throwable error) {
        if (error instanceof ExecutionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }
}
