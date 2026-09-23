package cn.sutone.ai.domain.agent.model.valobj;

import java.util.Set;

/**
 * 记忆生命周期状态（唯一真值源，禁止魔法字符串散落）。
 *
 * <p>这是记忆「时间态」的状态机：status 决定一条记忆在检索 / 注入时的可检索性，
 * 与 {@code is_deleted}（用户删除的唯一真值源）是 AND 关系但语义正交。</p>
 *
 * <p>可检索性两段式（对应 P1-3 附「status × 查询路径」矩阵）：
 * <ul>
 *   <li>默认注入 / 召回只取 {@link #ACTIVE}（{@code injectable=true}）</li>
 *   <li>通用历史查询（带 {@code updated_at} 时间限定）取 {@link #ACTIVE} / {@link #SUPERSEDED} / {@link #ARCHIVED}（{@code historyVisible=true}）</li>
 *   <li>{@link #DISPUTED} 既不可注入也不进通用历史查询，仅经独立冲突裁决入口 {@code selectDisputedByUserId} 可达</li>
 *   <li>{@link #PROBATION}/{@link #QUARANTINED}/{@link #MERGE_PENDING}/{@link #OBSERVATION} 仅审计 / 治理视图可见</li>
 * </ul></p>
 *
 * <p>状态不是线性阶段：ACTIVE 可直接转 DISPUTED，DORMANT 类语义由「低访问 + 未过期」推断，不单独设状态。</p>
 */
public enum MemoryStatus {

    /** 当前有效记忆：唯一注入源 */
    ACTIVE("ACTIVE", true, true),

    /** 观察期（低置信 / 未验证） */
    PROBATION("PROBATION", false, false),

    /** 被新版本取代（MySQL 保留原始行供追溯；Qdrant 旧向量删除） */
    SUPERSEDED("SUPERSEDED", false, true),

    /** 已归档（过期 / 清理 / 幻觉抽检） */
    ARCHIVED("ARCHIVED", false, true),

    /** 语义冲突未决：仅经冲突裁决入口可达 */
    DISPUTED("DISPUTED", false, false),

    /** 隔离（疑似幻觉 / 敏感） */
    QUARANTINED("QUARANTINED", false, false),

    /** 待合并（重复簇，硬化合并前不注入） */
    MERGE_PENDING("MERGE_PENDING", false, false),

    /** 观察层（熔断二级降级时归档的原始对话片段） */
    OBSERVATION("OBSERVATION", false, false);

    private final String code;

    /** 是否参与默认注入 / 召回 */
    private final boolean injectable;

    /** 是否经通用历史查询（selectHistoryByUserId）可见 */
    private final boolean historyVisible;

    MemoryStatus(String code, boolean injectable, boolean historyVisible) {
        this.code = code;
        this.injectable = injectable;
        this.historyVisible = historyVisible;
    }

    public String getCode() {
        return code;
    }

    public boolean isInjectable() {
        return injectable;
    }

    public boolean isHistoryVisible() {
        return historyVisible;
    }

    /** 默认注入 / 召回的状态码集合（SQL: WHERE status IN (...)） */
    public static Set<String> defaultInjectableCodes() {
        return Set.of(ACTIVE.code);
    }

    /** 通用历史查询可见的状态码集合（带时间限定） */
    public static Set<String> historyVisibleCodes() {
        return Set.of(ACTIVE.code, SUPERSEDED.code, ARCHIVED.code);
    }

    /** 按 code 解析，未知值缺省为 ACTIVE */
    public static MemoryStatus fromCode(String code) {
        for (MemoryStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        return ACTIVE;
    }
}
