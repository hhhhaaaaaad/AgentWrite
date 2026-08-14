package cn.sutone.ai.domain.agent.service.ai_writing.markdown;

import cn.sutone.ai.domain.agent.model.valobj.MarkdownPolicyVO;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.markdown.MarkdownRenderer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Markdown 规范化器（层二：AST 根治方案）。
 *
 * <p>用 CommonMark 把 LLM 输出解析成 AST，再用 MarkdownRenderer 反向序列化为标准 Markdown。
 * 解析器天然理解「标题/段落/表格」的结构边界，不会像正则那样误切合法标题（根治 A 类畸形），
 * 重新序列化时统一补空行、统一层级，对无穷的畸形样式更鲁棒（缓解 B 类畸形）。</p>
 *
 * <p>少量正则仅用于 parse 前的预处理：处理「标题正文无标点粘连」这类解析器无法从文本推断边界、
 * 以及行内 LaTeX 转义（$...$ 内的 \_、\= 不属于 Markdown 结构，AST 不会碰）的情况。</p>
 */
public final class MarkdownNormalizer {

    private static final List<org.commonmark.Extension> EXTENSIONS = List.of(TablesExtension.create());

    private static final Parser PARSER = Parser.builder()
            .extensions(EXTENSIONS)
            .build();

    private static final MarkdownRenderer RENDERER = MarkdownRenderer.builder()
            .extensions(EXTENSIONS)
            .build();

    private static final Pattern SUB_SECTION_PATTERN = Pattern.compile("^\\d+\\.\\d+");

    private MarkdownNormalizer() {
    }

    /**
     * 规范化 LLM 产出的 Markdown 文本。
     *
     * @param raw 原始文本，可能包含畸形格式
     * @return 规范化后的标准 Markdown；入参为空时原样返回
     */
    public static String normalize(String raw) {
        if (null == raw || raw.isBlank()) {
            return raw;
        }
        // 预处理：修复解析器无法处理的转义畸形与结构断裂
        String pre = preprocess(raw);
        try {
            Node document = PARSER.parse(pre);
            normalizeHeadingLevels(document);
            String rendered = RENDERER.render(document);
            String result = null == rendered ? pre.strip() : rendered.strip();
            // MarkdownRenderer 在标题内会对 ** / ` 等 inline markup 做转义（安全保守策略），
            // 但我们的场景中标题内的粗体/行内代码是有意为之的格式，需要还原
            result = postprocess(result);
            return finalCleanup(result);
        } catch (Exception e) {
            // 解析异常时降级为预处理结果，保证永不抛出
            return pre.strip();
        }
    }

    public static String normalize(String raw, MarkdownPolicyVO policy) {
        if (null == raw || raw.isBlank()) {
            return raw;
        }
        MarkdownPolicyVO safePolicy = null == policy ? MarkdownPolicyVO.ARTICLE_STRICT : policy;
        return switch (safePolicy) {
            case NONE -> raw;
            case PLAIN_TEXT -> normalizePlainText(raw);
            case PLAIN_LINES -> normalizePlainLines(raw);
            case TAGS -> normalizeTags(raw);
            case INLINE_LIGHT -> normalizeInlineLight(raw);
            case OUTLINE_LIGHT, ARTICLE_LIGHT, REPORT_LIGHT -> normalizeMarkdownLight(raw);
            case ARTICLE_STRICT -> normalize(raw);
        };
    }

    private static String normalizePlainText(String raw) {
        String text = stripMarkdownFence(removeConversationalPrefix(raw));
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\n", -1)) {
            String cleaned = line.strip()
                    .replaceAll("^#{1,6}\\s*", "")
                    .replaceAll("^[-*+]\\s+", "")
                    .replaceAll("^\\d+[.)、]\\s*", "")
                    .strip();
            if (cleaned.isBlank() || isPlainTextHeading(cleaned)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(cleaned);
        }
        return sb.toString().replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static String normalizePlainLines(String raw) {
        String text = stripMarkdownFence(removeConversationalPrefix(raw));
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\n")) {
            String cleaned = line.strip()
                    .replaceAll("^[-*+]\\s+", "")
                    .replaceAll("^\\d+[.)、]\\s*", "")
                    .replaceAll("^#{1,6}\\s*", "")
                    .strip();
            if (!cleaned.isBlank()) {
                lines.add(cleaned);
            }
            if (lines.size() >= 5) {
                break;
            }
        }
        return String.join("\n", lines);
    }

    private static String normalizeTags(String raw) {
        String text = stripMarkdownFence(removeConversationalPrefix(raw));
        String[] parts = text.split("[，,、;；\\n]");
        Set<String> tags = new LinkedHashSet<>();
        for (String part : parts) {
            String tag = part.strip()
                    .replaceAll("^[-*+]\\s+", "")
                    .replaceAll("^\\d+[.)、]\\s*", "")
                    .replaceAll("^#+", "")
                    .strip();
            if (!tag.isBlank()) {
                tags.add(tag);
            }
            if (tags.size() >= 5) {
                break;
            }
        }
        return String.join(", ", tags);
    }

    private static String normalizeInlineLight(String raw) {
        return finalCleanup(stripMarkdownFence(removeConversationalPrefix(raw))).strip();
    }

    private static String normalizeMarkdownLight(String raw) {
        return finalCleanup(preprocess(raw)).strip();
    }

    private static String stripMarkdownFence(String raw) {
        return raw.replaceAll("(?s)^```(?:markdown|md)?\\s*\\n(.*)\\n```\\s*$", "$1");
    }

    private static String removeConversationalPrefix(String raw) {
        return raw.replaceFirst("(?s)^\\s*(以下是|下面是|这是|为你生成的|根据.*?生成的)[^\\n]*[:：]\\s*", "");
    }

    private static boolean isPlainTextHeading(String text) {
        return "摘要".equals(text) || "文章摘要".equals(text) || "总结".equals(text);
    }

    /**
     * AST 级标题层级修正：## 2.1 xxx → ### 2.1 xxx（编号子章节统一为三级标题）。
     */
    private static void normalizeHeadingLevels(Node document) {
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                if (heading.getLevel() == 2) {
                    String text = extractPlainText(heading);
                    if (SUB_SECTION_PATTERN.matcher(text).find()) {
                        heading.setLevel(3);
                    }
                }
                visitChildren(heading);
            }
        });
    }

    private static String extractPlainText(Node node) {
        StringBuilder sb = new StringBuilder();
        Node child = node.getFirstChild();
        while (child != null) {
            if (child instanceof Text) {
                sb.append(((Text) child).getLiteral());
            }
            child = child.getNext();
        }
        return sb.toString();
    }

    /**
     * parse 前的预处理：修复解析器无法处理的转义畸形与结构断裂。
     * 覆盖模型常见的全部畸形类型：转义字符、标题粘连、列表缺空格、有序列表断裂、表格断裂等。
     */
    private static String preprocess(String raw) {
        String result = raw;

        // 1. 移除全文包裹的 ```markdown / ```md 围栏
        result = result.replaceAll("(?s)^```(?:markdown|md)\\s*\\n(.*)\\n```\\s*$", "$1");

        // 2. 错误转义修复（LLM 常见：\*\*、\*、\#、\|、\-、\>、\.）
        //    只在围栏代码块外执行
        result = fixEscapesOutsideCodeBlocks(result);

        // 3. 有序列表编号转义修复：「2\.」→「2.」（模型转义句号导致 Markdown 不识别为有序列表）
        result = result.replaceAll("(?m)^(\\s*)(\\d+)\\\\\\.", "$1$2.");

        // 4. 有序列表断行修复：「1.\n内容」（编号和内容分成两行）→「1. 内容」
        //    匹配：行首数字. 后跟换行（可能有空行）再跟内容
        result = result.replaceAll("(?m)^(\\d+\\.)\\s*\\n+\\s*([^\\s\\n#|>-])", "$1 $2");

        // 5. 列表标记后补空格：「-text」→「- text」、「*text」→「* text」
        result = result.replaceAll("(?m)^(\\s*[-*])([^\\s\\-*\\n])", "$1 $2");

        // 6. 行内标题拼接（两种情况）：
        //    6a. 「正文##标题」（## 后无空格）→「正文\n\n## 标题」
        result = result.replaceAll("(?m)([^\\n#])(#{2,6})([^\\s\\n#])", "$1\n\n$2 $3");
        //    6b. 「正文## 标题」（## 后有空格，标准写法但粘在行内）→「正文\n\n## 标题」
        result = result.replaceAll("(?m)([^\\n#])(#{2,6})\\s", "$1\n\n$2 ");

        // 7. 合并重复标题标记：「## # text」→「### text」、「### # text」→「#### text」
        //    LLM 有时在 ## 后面又多写一个 # 当装饰，应合并为更深一级
        result = result.replaceAll("(?m)^(#{1,5})\\s+#\\s+", "$1# ");

        // 8. 行首标题标记后补空格：「###Title」→「### Title」
        result = result.replaceAll("(?m)^(#{1,6})([^\\s#\\n])", "$1 $2");

        // 8. 标题与正文粘连在同一行：标题后紧跟**或中文字符的长文本
        //    如「### 6.3直接内存的优缺点与调优**优点：**」→ 拆为标题 + 正文
        result = result.replaceAll("(?m)^(#{1,6}\\s+[^\\n]{2,60}?)(\\*\\*[^\\n]+)$", "$1\n\n$2");

        // 9. 标题与表格粘连：标题行末尾直接跟 | 表头
        result = result.replaceAll("(?m)^(#{1,6}\\s+[^\\n|]+)(\\|[^\\n]+\\|)$", "$1\n\n$2");

        // 10. 编号标题断行修复：「### 2.\n\n1 xxx」→「### 2.1 xxx」
        result = result.replaceAll("(?m)^(#{1,6}\\s+\\d+\\.)\\s*\\n+\\s*(\\d+)", "$1$2");

        // 11. LaTeX 公式内错误转义：\_ -> _、\= -> =
        result = result.replaceAll("(?s)(\\$\\$?[^$]*?)\\\\_", "$1_");
        result = result.replaceAll("(?s)(\\$\\$?[^$]*?)\\\\=", "$1=");

        // 12. 清除排版标注残留
        result = result.replaceAll("\\s*\\[(?:粗体|列表|表格|引用)\\]", "");

        // 13. 表格行之间的空行移除（空行会打断 GFM 表格解析）
        result = result.replaceAll("(?m)(\\|[^\\n]+\\|)\\n\\n(\\|)", "$1\n$2");

        // 14. 表格首行前如果缺空行（前一行是非空非表格行），补空行让 AST 正确识别
        result = result.replaceAll("(?m)([^|\\n\\s][^\\n]*)\\n(\\|[^\\n]+\\|\\s*\\n\\|[-\\s|]+\\|)", "$1\n\n$2");

        // 15. <br /> 标签清除（模型偶尔输出 HTML 换行标签）
        result = result.replaceAll("(?i)<br\\s*/?>", "\n");

        // 16. 代码块围栏语言标识与代码粘连：「```javapublic ...」→「```java\npublic ...」
        //     模型常把语言标识和首行代码写在同一行，导致 CommonMark 把 javapublic 当成语言标识。
        //     仅对常见语言做拆分；语言后紧跟非空白内容时，在语言标识后补换行。
        result = result.replaceAll(
                "(?m)^(\\s*```)(javascript|typescript|kotlin|python|properties|yaml|bash|shell|json|html|java|rust|yml|sql|xml|css|cpp|go|js|ts|py|sh|c)(\\S.*)$",
                "$1$2\n$3");

        return result;
    }

    /**
     * 仅在围栏代码块（```...```）之外修复转义字符。
     * 避免破坏代码块中合法的 \*、\| 等字面字符。
     */
    private static String fixEscapesOutsideCodeBlocks(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        boolean insideCodeBlock = false;
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            String trimmed = line.trim();
            // 检测围栏代码块的开启/关闭行（```或```java 等）
            if (trimmed.startsWith("```")) {
                insideCodeBlock = !insideCodeBlock;
                sb.append(line);
            } else if (insideCodeBlock) {
                sb.append(line);
            } else {
                // 在代码块外修复转义
                String fixed = line;
                fixed = fixed.replace("\\*\\*", "**");  // \*\* -> **（转义粗体，优先处理成对的）
                fixed = fixed.replace("\\*", "*");      // \* -> *
                fixed = fixed.replace("\\#", "#");      // \# -> #
                fixed = fixed.replace("\\|", "|");      // \| -> |（表格竖线）
                fixed = fixed.replace("\\-", "-");      // \- -> -（列表标记）
                fixed = fixed.replace("\\>", ">");      // \> -> >（引用块）
                fixed = fixed.replace("\\.", ".");       // \. -> .（有序列表句号）
                fixed = fixed.replace("\\`", "`");      // \` -> `（行内代码）
                sb.append(fixed);
            }
        }
        return sb.toString();
    }

    /**
     * AST 渲染后的后处理：MarkdownRenderer 在标题内会对 ** ` 等 inline markup 做保守转义
     * （如 \*\*、\`），但在技术文章场景中标题内粗体/行内代码是有意为之的格式，需要还原。
     * 仅在围栏代码块外执行。
     */
    private static String postprocess(String rendered) {
        // 复用围栏代码块感知的转义还原逻辑
        return fixEscapesOutsideCodeBlocks(rendered);
    }

    /**
     * 最终安全网：代码块外彻底清除 Markdown 转移反斜杠。
     * 作为 AST/preprocess/postprocess 之后的最后防线。
     */
    private static String finalCleanup(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        boolean inside = false;
        for (String line : lines) {
            if (sb.length() > 0) sb.append('\n');
            String t = line.trim();
            if (t.startsWith("```")) { inside = !inside; sb.append(line); continue; }
            if (inside) { sb.append(line); continue; }
            sb.append(line
                .replace("\\*\\*", "**").replace("\\*", "*")
                .replace("\\#", "#").replace("\\|", "|")
                .replace("\\-", "-").replace("\\>", ">")
                .replace("\\.", ".").replace("\\`", "`"));
        }
        return sb.toString();
    }
}
