package com.hmdp.agent.observability.api;

import com.hmdp.agent.observability.api.AgentSpan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 观测感知的 SSE 发射器：SSE 流生命周期与会话根 span（agent.session）生命周期绑定。
 * <p>
 * 背景（2026-08-04 生产断链）：根 span 的结束原依赖 {@code emitter.complete()} 后的
 * onCompletion 回调，实测该回调在客户端连接状态异常时可能不触发（WebAsyncManager 检测
 * {@code isAsyncComplete()==true} 直接 return，不 dispatch）→ 根 span 永不 stop、永不导出
 * → Langfuse/LogView 中所有子 span 平铺。
 * </p>
 * <p>
 * 设计（三子模型对抗审查后定稿，见 md/agent/observability/ObservedSseEmitter设计方案.md）：
 * <ul>
 *   <li><b>生命周期绑定</b>：任何 complete / completeWithError / 容器超时 / 容器错误 /
 *       兜底 TTL 路径都收敛到 {@link #finish(String)}，结束根 span（{@link AgentSpan#end()} 幂等）</li>
 *   <li><b>only-once 语义</b>：{@link #finishReason} 的 CAS 保证首个 reason 生效；
 *       finish 属性在 end 前写入（onStop 同步到 span），多线程竞争无竞态</li>
 *   <li><b>兜底 TTL</b>（构造参数 guardDelayMs）：正常完成时 {@link ScheduledFuture#cancel}
 *       取消延迟任务，防高并发任务堆积；任务触发时兼做 {@code super.complete()}（CAS 幂等）
 *       防 async context 僵尸</li>
 *   <li><b>root 判空降级</b>：root 为 null 时退化为普通 emitter（不触碰任何 span）</li>
 * </ul>
 * </p>
 */
@Slf4j
public class ObservedSseEmitter extends SseEmitter {

    private final AgentSpan root;
    /** null=PENDING；CAS 到非 null 即完成（首个 reason 生效） */
    private final AtomicReference<String> finishReason = new AtomicReference<>(null);
    private final ScheduledFuture<?> timeoutGuard;

    /**
     * @param timeoutMs     SSE 流超时（同 {@link SseEmitter#SseEmitter(long)}）
     * @param root          会话根 span（可为 null，降级为普通 emitter）
     * @param scheduler     兜底 TTL 的调度器（可为 null，则无 TTL 保底）
     * @param guardDelayMs  兜底 TTL：超过该时长仍未结束则强制 end（须 > timeoutMs）
     */
    public ObservedSseEmitter(long timeoutMs, AgentSpan root, TaskScheduler scheduler, long guardDelayMs) {
        super(timeoutMs);
        // M-1（设计 §8）：guard 是容器超时(30min)之后的最后防线，须严格晚于 timeoutMs。
        // 否则误配置会先于容器超时触发，把健康流当 TIMEOUT 掐断——fail-fast 优于静默截流。
        // 仅当确有 guard（scheduler 非 null）时校验；scheduler=null 时 guardDelayMs 是死参数，不校验。
        if (scheduler != null && guardDelayMs <= timeoutMs) {
            throw new IllegalArgumentException(
                    "guardDelayMs 必须 > timeoutMs（guard 为容器超时后的最后防线）: guardDelayMs="
                            + guardDelayMs + ", timeoutMs=" + timeoutMs);
        }
        this.root = root;
        // 容器超时 + 容器错误 都收敛到 finish（addDelegate 追加语义，注册先于 Controller 执行）
        super.onTimeout(() -> finish("TIMEOUT"));
        super.onError(ex -> finish("ERROR"));
        // 兜底 TTL：最后防线（容器回调也不触发时仍保证根 span 结束）。
        // 注意 M-2：guardDelayMs 自【构造】起算，而容器超时自 startAsync（controller 返回后）起算，
        // 同步段耗时 < (guardDelayMs - timeoutMs) 时才保证"容器先触发"，当前同步段秒级，成立。
        this.timeoutGuard = scheduler != null
                ? scheduler.schedule(this::finishGuard, Instant.now().plusMillis(guardDelayMs))
                : null;
    }

    @Override
    public void complete() {
        finish("COMPLETE");
        super.complete();
    }

    @Override
    public void completeWithError(Throwable ex) {
        finish("ERROR");
        super.completeWithError(ex);
    }

    /** 兜底 TTL 触发（only-once 保证不覆盖业务侧已写入的 reason）；兼 complete 防 async 僵尸 */
    private void finishGuard() {
        finish("TIMEOUT");
        try {
            super.complete();   // 终态后静默 no-op（ResponseBodyEmitter.trySetComplete CAS），try/catch 仅为兜底
        } catch (Exception ignored) {
            // 无害：span 已由 finish 结束
        }
    }

    /**
     * only-once 结束根 span：首个 reason 生效（CAS），finish 属性在 end 前写入
     * （onStop 同步到 span），结束后取消兜底任务（防堆积）。
     * <p>
     * 异常安全（设计 §8 N1）：{@code root.end()} 用 finally 兜底——终态漏斗是 span 的唯一
     * 收敛点，即使 {@code attribute()} 抛异常也必须保证 end() 执行，否则 CAS 已置位、
     * 无任何路径重试，span 永久泄漏（违反"根 span 必然结束"）。
     * </p>
     */
    private void finish(String reason) {
        if (finishReason.compareAndSet(null, reason)) {
            if (timeoutGuard != null) {
                timeoutGuard.cancel(false);
            }
            if (root != null) {
                try {
                    root.attribute("finish", reason);
                } finally {
                    root.end();
                }
            }
            log.info("SSE 会话结束 reason={} thread={}", reason, Thread.currentThread().getName());
        }
    }
}
