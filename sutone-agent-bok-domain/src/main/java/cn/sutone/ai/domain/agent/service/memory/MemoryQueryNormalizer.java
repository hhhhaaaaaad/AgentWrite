package cn.sutone.ai.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.model.valobj.MemoryRetrieveQueryVO;
import cn.sutone.ai.domain.agent.model.valobj.NormalizedMemoryQueryVO;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆检索 query 标准化器
 */
@Component
public class MemoryQueryNormalizer {

    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_SUMMARY_LENGTH = 300;
    private static final int MAX_CONTENT_LENGTH = 500;
    private static final int MAX_SELECTED_TEXT_LENGTH = 500;
    private static final int MAX_CUSTOM_INSTRUCTION_LENGTH = 200;
    private static final int MAX_FORMAT_INSTRUCTION_LENGTH = 120;
    private static final int MAX_LEXICAL_TERMS = 10;
    private static final int MAX_LEXICAL_LENGTH = 80;

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("(?s)```.*?```");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("(?m)^#{1,6}\\s*");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern ENGLISH_TOKEN_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9+._/-]{1,30}");
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[，。；：、,.;:!?！？()（）\\[\\]{}<>《》“”\"'\\s]+");

    private static final Set<String> LEXICAL_STOP_WORDS = Set.of(
            "请", "帮我", "帮忙", "当前", "内容", "进行", "基于", "不要", "输出", "说明",
            "要求", "可以", "如果", "已经", "针对", "一个", "这个", "那个", "以及", "其中",
            "通过", "我们", "你是", "文章", "草稿", "正文", "标题", "摘要", "用户", "额外参数"
    );

    public NormalizedMemoryQueryVO normalize(MemoryRetrieveQueryVO raw) {
        MemoryRetrieveQueryVO normalizedRaw = MemoryRetrieveQueryVO.builder()
                .taskType(safe(raw != null ? raw.getTaskType() : null))
                .title(normalizeField(raw != null ? raw.getTitle() : null, MAX_TITLE_LENGTH))
                .summary(normalizeField(raw != null ? raw.getSummary() : null, MAX_SUMMARY_LENGTH))
                .contentMd(normalizeField(raw != null ? raw.getContentMd() : null, MAX_CONTENT_LENGTH))
                .selectedText(normalizeField(raw != null ? raw.getSelectedText() : null, MAX_SELECTED_TEXT_LENGTH))
                .customInstruction(normalizeField(raw != null ? raw.getCustomInstruction() : null, MAX_CUSTOM_INSTRUCTION_LENGTH))
                .formatInstruction(normalizeField(raw != null ? raw.getFormatInstruction() : null, MAX_FORMAT_INSTRUCTION_LENGTH))
                .build();

        String queryMode = resolveQueryMode(normalizedRaw);
        String semanticQuery = buildSemanticQuery(normalizedRaw, queryMode);
        String lexicalQuery = buildLexicalQuery(normalizedRaw);
        String canonicalText = buildCanonicalText(normalizedRaw, queryMode);
        String cacheKeyDigest = DigestUtils.md5Hex(canonicalText);

        return NormalizedMemoryQueryVO.builder()
                .queryMode(queryMode)
                .semanticQuery(semanticQuery)
                .lexicalQuery(lexicalQuery)
                .canonicalText(canonicalText)
                .cacheKeyDigest(cacheKeyDigest)
                .build();
    }

    private String resolveQueryMode(MemoryRetrieveQueryVO raw) {
        String taskType = safe(raw.getTaskType()).toUpperCase(Locale.ROOT);
        if ("POLISH_TEXT".equals(taskType)) {
            return isBlank(raw.getSelectedText()) ? "POLISH_FULL" : "POLISH_SELECTION";
        }
        if ("GENERATE_OUTLINE".equals(taskType)) {
            return "OUTLINE";
        }
        if ("GENERATE_BODY".equals(taskType)) {
            return "BODY_CONTINUATION";
        }
        return isBlank(taskType) ? "LEGACY" : taskType;
    }

    private String buildSemanticQuery(MemoryRetrieveQueryVO raw, String queryMode) {
        if (!hasMeaningfulContent(raw)) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        segments.add("任务模式：" + queryMode);
        appendLabelled(segments, "任务类型", raw.getTaskType());
        appendLabelled(segments, "文章标题", raw.getTitle());
        appendLabelled(segments, "文章摘要", raw.getSummary());
        if (!isBlank(raw.getSelectedText())) {
            appendLabelled(segments, "待处理文本", raw.getSelectedText());
        } else {
            appendLabelled(segments, "当前正文片段", raw.getContentMd());
        }
        appendLabelled(segments, "用户额外指令", raw.getCustomInstruction());
        appendLabelled(segments, "格式约束", raw.getFormatInstruction());
        return joinNonBlank(segments, "\n");
    }

    private String buildLexicalQuery(MemoryRetrieveQueryVO raw) {
        if (!hasMeaningfulContent(raw)) {
            return "";
        }
        Map<String, Integer> weightedTerms = new LinkedHashMap<>();
        addWeightedTerms(weightedTerms, raw.getTaskType(), 5);
        addWeightedTerms(weightedTerms, raw.getTitle(), 4);
        addWeightedTerms(weightedTerms, raw.getSummary(), 3);
        if (!isBlank(raw.getSelectedText())) {
            addWeightedTerms(weightedTerms, raw.getSelectedText(), 5);
        } else {
            addWeightedTerms(weightedTerms, raw.getContentMd(), 2);
        }
        addWeightedTerms(weightedTerms, raw.getCustomInstruction(), 3);

        List<String> ordered = weightedTerms.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        int length = 0;
        for (String item : ordered) {
            if (selected.size() >= MAX_LEXICAL_TERMS) {
                break;
            }
            int nextLength = length + (selected.isEmpty() ? 0 : 1) + item.length();
            if (nextLength > MAX_LEXICAL_LENGTH && !selected.isEmpty()) {
                continue;
            }
            selected.add(item);
            length = nextLength;
        }
        return String.join(" ", selected);
    }

    private String buildCanonicalText(MemoryRetrieveQueryVO raw, String queryMode) {
        List<String> segments = new ArrayList<>();
        segments.add("queryMode=" + queryMode);
        appendCanonical(segments, "taskType", raw.getTaskType());
        appendCanonical(segments, "title", raw.getTitle());
        appendCanonical(segments, "summary", raw.getSummary());
        appendCanonical(segments, "selectedText", raw.getSelectedText());
        appendCanonical(segments, "customInstruction", raw.getCustomInstruction());
        appendCanonical(segments, "formatInstruction", raw.getFormatInstruction());
        if (isBlank(raw.getSelectedText())) {
            appendCanonical(segments, "contentSnippet", raw.getContentMd());
        }
        return joinNonBlank(segments, "\n");
    }

    private void appendLabelled(List<String> segments, String label, String value) {
        if (!isBlank(value)) {
            segments.add(label + "：" + value);
        }
    }

    private void appendCanonical(List<String> segments, String label, String value) {
        if (!isBlank(value)) {
            segments.add(label + "=" + value);
        }
    }

    private void addWeightedTerms(Map<String, Integer> weightedTerms, String text, int weight) {
        if (isBlank(text)) {
            return;
        }
        for (String token : extractLexicalTerms(text)) {
            weightedTerms.merge(token, weight, Integer::sum);
        }
    }

    private List<String> extractLexicalTerms(String text) {
        String cleaned = normalizeField(text, MAX_CONTENT_LENGTH);
        if (isBlank(cleaned)) {
            return List.of();
        }

        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher englishMatcher = ENGLISH_TOKEN_PATTERN.matcher(cleaned);
        while (englishMatcher.find()) {
            String token = englishMatcher.group().trim();
            if (token.length() >= 2) {
                terms.add(token);
            }
        }

        Arrays.stream(SPLIT_PATTERN.split(cleaned))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(this::normalizeLexicalTerm)
                .filter(Objects::nonNull)
                .forEach(terms::add);

        return new ArrayList<>(terms);
    }

    private String normalizeLexicalTerm(String raw) {
        String term = raw.trim();
        if (term.length() < 2) {
            return null;
        }
        if (term.length() > 24) {
            term = term.substring(0, 24).trim();
        }
        if (term.isBlank()) {
            return null;
        }
        if (LEXICAL_STOP_WORDS.contains(term)) {
            return null;
        }
        if (term.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return term;
    }

    private String normalizeField(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value;
        normalized = CODE_BLOCK_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = MARKDOWN_IMAGE_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = MARKDOWN_LINK_PATTERN.matcher(normalized).replaceAll("$1");
        normalized = INLINE_CODE_PATTERN.matcher(normalized).replaceAll("$1");
        normalized = URL_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = MARKDOWN_HEADING_PATTERN.matcher(normalized).replaceAll("");
        normalized = normalized.replace('*', ' ')
                .replace('_', ' ')
                .replace('`', ' ')
                .replace('|', ' ')
                .replace('>', ' ');
        normalized = WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ").trim();
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength).trim();
        }
        return normalized;
    }

    private String joinNonBlank(List<String> segments, String delimiter) {
        return segments.stream()
                .filter(s -> !isBlank(s))
                .reduce((a, b) -> a + delimiter + b)
                .orElse("");
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasMeaningfulContent(MemoryRetrieveQueryVO raw) {
        if (raw == null) {
            return false;
        }
        return !isBlank(raw.getTitle())
                || !isBlank(raw.getSummary())
                || !isBlank(raw.getContentMd())
                || !isBlank(raw.getSelectedText())
                || !isBlank(raw.getCustomInstruction())
                || !isBlank(raw.getFormatInstruction());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
