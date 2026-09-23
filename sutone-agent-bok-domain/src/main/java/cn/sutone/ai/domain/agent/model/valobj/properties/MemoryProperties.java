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

    @Data
    public static class Qdrant {
        private String url = "http://localhost:6333";
        private String collection = "agent_memory";
        /** 向量维度，不配则自动探测 */
        private Integer vectorSize;
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
