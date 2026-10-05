package cn.sutone.ai.domain.agent.model.valobj.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆系统完整配置（Qdrant、Reranker、Cache、Importance、Extraction）
 */
@Data
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {

    /** 向量存储：memory（V1 内存） | qdrant */
    private String vectorStore = "memory";

    private Qdrant qdrant = new Qdrant();
    private Reranker reranker = new Reranker();
    private Cache cache = new Cache();
    private Importance importance = new Importance();
    private Extraction extraction = new Extraction();
    private Retrieval retrieval = new Retrieval();
    private Inject inject = new Inject();
    private Alert alert = new Alert();
    private Eval eval = new Eval();

    @Data
    public static class Qdrant {
        private String url = "http://localhost:6333";
        private String collection = "agent_memory";
        /** 向量维度，不配则自动探测 */
        private Integer vectorSize;
        /**
         * 写操作是否等 Qdrant 把点索引完再返回（默认 false = Qdrant 自己的默认）。
         *
         * <p><b>为什么默认关</b>：开启后每次 upsert/delete 都要等索引，向量同步任务做批量
         * 补偿时会明显变慢。生产路径不承担这个代价。</p>
         *
         * <p><b>为什么评测实例要开</b>：Qdrant 的 {@code wait=false} 意味着接口返回只代表
         * 「已接受」（响应里 {@code result.status = acknowledged}），点要**索引完成后才可被搜到**。
         * 而评测的 VECTOR_READY 屏障读的是 MySQL 侧的「已上传」计数——那是「已接受」、
         * 不是「可搜性」，于是屏障可能在索引完成前就放行。评测的写入是一次性的
         * 几十到几百个点，等索引的代价可忽略。</p>
         */
        private boolean waitForIndexing = false;
    }

    @Data
    public static class Reranker {
        private boolean enabled = false;
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private String apiKey;
        private String model = "BAAI/bge-reranker-v2-m3";
        private int timeout = 3000;
    }

    @Data
    public static class Cache {
        private int profileTtlMinutes = 10;
        private int profileMaxItems = 20;
        private int searchTtlMinutes = 2;
        private double hotImportanceThreshold = 0.7;
    }

    @Data
    public static class Importance {
        private double baseWeight = 0.5;
        private double frequencyWeight = 0.3;
        private double recencyWeight = 0.2;
        private double min = 0.1;
        private double max = 1.0;
    }

    @Data
    public static class Extraction {
        private int maxContentLength = 500;
    }

    @Data
    public static class Retrieval {
        /** RRF 融合常数 k（默认 60） */
        private int rrfK = 60;
        /** recency 重排因子权重 α（默认 0.1） */
        private double alpha = 0.1;
        /** importance 重排因子权重 β（默认 0.1） */
        private double beta = 0.1;
        /** recency 指数衰减半衰期（天，默认 30） */
        private double recencyHalfLifeDays = 30;
        /** 画像候选布尔 boost 比例（命中画像 × (1+boost)，默认 0.15） */
        private double profileBoost = 0.15;
        /** 回表后的最低置信度阈值（默认 0.0 不过滤） */
        private double minConfidence = 0.0;
    }

    @Data
    public static class Inject {
        /** 单次注入 token 预算（默认 800，中文按 1 token/字 估算） */
        private int maxTokens = 800;
    }

    /**
     * 评测模式配置（独立评测平台调用 Java eval 实例时启用）。
     *
     * <p>{@code enabled=true} 时：禁用业务 cron（治理/向量同步）、连接专用库与 collection。
     * {@code baseUserId}/{@code userIdRange} 定义 eval_user_id 合法命名空间，服务端据此校验
     * 防止越界访问业务 userId（IDOR 防护）。派生规则见《方案》per-config derived userId。</p>
     */
    @Data
    public static class Eval {
        /** 评测模式开关：true 时禁用业务 cron、使用专用库/集合 */
        private boolean enabled = false;
        /** eval_user_id 派生基数（避开业务 userId 段） */
        private long baseUserId = 9_000_000_000L;
        /** eval_user_id 取值范围大小（派生 id ∈ [base, base+range)） */
        private long userIdRange = 1_000_000L;
    }

    /**
     * 告警规则配置（P3-2，对应实施计划 §2.3.2）。
     *
     * <p><b>首月不启用告警，仅埋点</b>（{@code enabled} 默认 false）。阈值均为豆包初始值，
     * 基线收集后启用，再依分布校准。本配置暂不接真实通知渠道——指标类告警后续由
     * Prometheus/Grafana Alerting 消费 {@code /actuator/prometheus}，错误样本走 ELK。</p>
     */
    @Data
    public static class Alert {
        /** 首月是否启用告警（默认 false，仅埋点记录，不触发通知） */
        private boolean enabled = false;
        /** 抽取驳回率告警阈值（>20% 告警，P1） */
        private double extractionRejectRate = 0.2;
        /** 重复率告警阈值（>10% 告警，P1） */
        private double duplicateRate = 0.1;
        /** 冲突记忆占比告警阈值（>5% 告警，P1） */
        private double conflictRate = 0.05;
        /** 过期召回率告警阈值（>8% 告警，P1） */
        private double expiredRecallRate = 0.08;
        /** 纠错率告警阈值（>10% 告警，P0；纠错信号机制落地前不可观测，暂不启用） */
        private double correctionRate = 0.1;
        /** 判断窗口（分钟，默认 15） */
        private int windowMinutes = 15;
        /** 派生率 Gauge 评估间隔（毫秒，默认 60000） */
        private long evalIntervalMs = 60000;
    }
}
