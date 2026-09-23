package cn.sutone.ai.domain.agent.service.memory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * predicate 归一化工具（P1-4）。
 *
 * <p>稳定属性身份键由 {@code (user_id, subject, predicate)} 唯一确定，若
 * {@code tech_stack} / {@code 技术栈} / {@code techStack} 被当作不同 predicate，
 * 身份化 UPDATE 会静默失败（同义属性各自独立成行）。因此抽取落库前必须把 predicate
 * 归一为统一规范形式：</p>
 * <ul>
 *   <li>小写化</li>
 *   <li>下划线 / 空格 / camelCase → 连字符</li>
 *   <li>中英同义映射（内置常用映射，可按需扩展；后续可迁移到候选词表 {@code memory_predicate_vocab}）</li>
 * </ul>
 *
 * <p>归一化是幂等的：对已归一化结果再次调用结果不变。</p>
 */
public final class PredicateNormalizer {

    private PredicateNormalizer() {
    }

    /** 中英同义映射：key 为常见原始形式（小写），value 为归一化后的英文键 */
    private static final Map<String, String> SYNONYMS = buildSynonyms();

    private static Map<String, String> buildSynonyms() {
        Map<String, String> m = new LinkedHashMap<>();
        // 技术栈
        m.put("技术栈", "tech-stack");
        m.put("技术", "tech-stack");
        m.put("编程语言", "tech-stack");
        m.put("tech_stack", "tech-stack");
        m.put("techstack", "tech-stack");
        // 写作偏好
        m.put("写作偏好", "preferred-style");
        m.put("写作风格", "preferred-style");
        m.put("偏好", "preferred-style");
        m.put("preferred_style", "preferred-style");
        m.put("preferredstyle", "preferred-style");
        // 角色
        m.put("角色", "role");
        m.put("职位", "role");
        // 位置
        m.put("所在城市", "location");
        m.put("城市", "location");
        // 公司
        m.put("公司", "company");
        m.put("工作单位", "company");
        // 姓名
        m.put("姓名", "name");
        // 联系方式
        m.put("联系方式", "contact");
        return m;
    }

    /**
     * 归一化 predicate：小写化、下划线/空格/驼峰 → 连字符、中英同义映射。
     *
     * @param predicate 原始 predicate（可为 null / 空白）
     * @return 归一化后的 predicate，空值返回 {@code null}
     */
    public static String normalize(String predicate) {
        if (predicate == null || predicate.isBlank()) {
            return null;
        }
        String s = predicate.trim();
        // 1) 原始形式同义映射（兼容 "技术栈"、"tech_stack"、"techStack" 等直接命中）
        String mapped = SYNONYMS.get(s.toLowerCase());
        if (mapped != null) {
            return mapped;
        }
        // 2) camelCase 拆词：techStack -> tech-Stack
        s = s.replaceAll("([a-z0-9])([A-Z])", "$1-$2");
        // 3) 下划线 / 连续空白 -> 连字符
        s = s.replace('_', '-').replaceAll("\\s+", "-");
        // 4) 小写化
        s = s.toLowerCase();
        // 5) 归一化后再次查映射（兜底）
        String mapped2 = SYNONYMS.get(s);
        return mapped2 != null ? mapped2 : s;
    }

    /**
     * 归一化属性值（用于「同 predicate 异 value」比较）。
     *
     * <p>value 是自由文本，语义归一化需 LLM，这里只做确定性的小写化 + 去首尾/折叠空白，
     * 用于幂等判等（{@code "Java 17"} 与 {@code "java  17"} 视为同值）。</p>
     */
    public static String normalizeValue(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
