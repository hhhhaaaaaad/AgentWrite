package cn.sutone.ai.domain.agent.service.memory.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 记忆系统全链路 trace_id（P0-7）。
 *
 * <p><b>传播契约</b>：跨线程 / 异步 / 定时边界的 trace_id 一律通过「显式方法参数」透传
 * （如 {@code persistSurvivors(..., traceId)}、记录 {@code trace_id} 列、事件体字段）。
 * <b>禁止依赖 ThreadLocal</b>——记忆链路会跨 {@code memoryExecutor} 异步线程、
 * {@code @Scheduled} 定时线程与检索线程，ThreadLocal 在每次线程切换即断链。</p>
 *
 * <p><b>MDC 用途</b>：仅用于「本线程」的日志关联——进入某线程执行任务时 {@link #next()} 写入 MDC，
 * 结束时 {@link #clear()} 清理。<b>绝不</b>从 MDC 读取 trace_id 作为跨线程传播来源。</p>
 */
public final class MemoryTraceId {

    /** MDC key，本线程日志关联用 */
    public static final String MDC_KEY = "memory_trace_id";

    private MemoryTraceId() {
    }

    /**
     * 生成新的 trace_id 并写入当前线程 MDC。
     *
     * @return 形如 {@code mem-<24位无横杠十六进制>} 的 id
     */
    public static String next() {
        String traceId = "mem-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        MDC.put(MDC_KEY, traceId);
        return traceId;
    }

    /**
     * 读取当前线程 MDC 中绑定的 trace_id。
     *
     * <p>仅用于本线程日志关联；跨线程传播请使用显式方法参数，勿依赖本方法。</p>
     *
     * @return 当前线程的 trace_id，未设置时返回 {@code null}
     */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /**
     * 清理当前线程 MDC 中的 trace_id（线程任务结束时调用，避免线程池复用串味）。
     */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
