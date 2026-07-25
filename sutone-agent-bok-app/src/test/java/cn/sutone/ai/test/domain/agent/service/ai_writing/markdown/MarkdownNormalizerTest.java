package cn.sutone.ai.test.domain.agent.service.ai_writing.markdown;

import cn.sutone.ai.domain.agent.model.valobj.MarkdownPolicyVO;
import cn.sutone.ai.domain.agent.service.ai_writing.markdown.MarkdownNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MarkdownNormalizer 单元测试")
class MarkdownNormalizerTest {

    @Test
    @DisplayName("null/空白输入应原样返回")
    void shouldReturnRawWhenBlank() {
        assertNull(MarkdownNormalizer.normalize(null));
        assertEquals("", MarkdownNormalizer.normalize(""));
        assertEquals("   ", MarkdownNormalizer.normalize("   "));
    }

    @Test
    @DisplayName("A 类根治：含冒号的合法标题不应被误切")
    void shouldNotSplitLegalHeadingWithColon() {
        // 老正则 11A 会把「## 六、实战：」从冒号后切开；AST 方案不应误切
        String raw = "## 六、实战：JVM 调优\n\n通过 JVM 参数调优内存。";
        String result = MarkdownNormalizer.normalize(raw);
        assertTrue(result.contains("六、实战：JVM 调优"), "标题应保持完整: " + result);
        // 标题与正文应各自独立成行（标题行不含正文）
        String headingLine = result.lines().filter(l -> l.startsWith("#")).findFirst().orElse("");
        assertFalse(headingLine.contains("通过 JVM"), "标题行不应混入正文: " + headingLine);
    }

    @Test
    @DisplayName("标题后缺空格应补齐")
    void shouldFixHeadingSpacing() {
        String result = MarkdownNormalizer.normalize("###标题\n正文");
        assertTrue(result.contains("### 标题"), "标题标记后应补空格: " + result);
    }

    @Test
    @DisplayName("行内拼接的标题应换行")
    void shouldSplitInlineHeading() {
        String result = MarkdownNormalizer.normalize("上一段##新标题");
        assertTrue(result.contains("## 新标题"), "行内标题应被拆出: " + result);
    }

    @Test
    @DisplayName("错误转义的粗体标记应修复")
    void shouldFixEscapedBold() {
        String result = MarkdownNormalizer.normalize("这是 \\*\\*重点\\*\\* 内容");
        assertTrue(result.contains("**重点**"), "转义粗体应还原: " + result);
    }

    @Test
    @DisplayName("全文被 ```markdown 包裹应去壳")
    void shouldStripMarkdownFence() {
        String raw = "```markdown\n# 标题\n\n正文\n```";
        String result = MarkdownNormalizer.normalize(raw);
        assertFalse(result.startsWith("```markdown"), "不应保留 markdown 围栏: " + result);
        assertTrue(result.contains("# 标题"), "应保留内部标题: " + result);
    }

    @Test
    @DisplayName("合法表格应被保留")
    void shouldPreserveTable() {
        String raw = "| 区域 | 异常 |\n| --- | --- |\n| 堆 | OOM |";
        String result = MarkdownNormalizer.normalize(raw);
        assertTrue(result.contains("| 区域 |") || result.contains("|区域|"), "表格结构应保留: " + result);
        assertTrue(result.contains("堆"), "表格数据应保留: " + result);
    }

    @Test
    @DisplayName("代码块语言标注应保留")
    void shouldPreserveCodeBlockLang() {
        String raw = "```java\nSystem.out.println(1);\n```";
        String result = MarkdownNormalizer.normalize(raw);
        assertTrue(result.contains("```java"), "代码块语言应保留: " + result);
    }

    @Test
    @DisplayName("转义的标题标记 \\# 应还原为 # 并恢复标题结构")
    void shouldFixEscapedHashHeadings() {
        String raw = "\\## 一、概述\\### 1.1 什么是 JVM";
        String result = MarkdownNormalizer.normalize(raw);
        assertTrue(result.contains("## 一、概述") || result.contains("# 一、概述"),
                "转义标题应还原: " + result);
    }

    @Test
    @DisplayName("转义的粗体 \\*\\* 应还原为 **")
    void shouldFixEscapedBoldPairs() {
        String raw = "## 三、Java虚拟机栈\\*\\*Java虚拟机栈（JVM Stack）\\*\\*描述的是线程内存模型。";
        String result = MarkdownNormalizer.normalize(raw);
        assertTrue(result.contains("**Java虚拟机栈"), "转义粗体应还原: " + result);
        assertFalse(result.contains("\\*\\*"), "不应残留转义粗体: " + result);
    }

    @Test
    @DisplayName("转义的表格竖线 \\| 应还原为 |")
    void shouldFixEscapedPipes() {
        String raw = "\\| 参数 \\| 作用 \\|\n\\|---\\|---\\|\n\\| -Xms \\| 初始堆 \\|";
        String result = MarkdownNormalizer.normalize(raw);
        assertFalse(result.contains("\\|"), "不应残留转义竖线: " + result);
        assertTrue(result.contains("| 参数") || result.contains("|参数"), "表格应正常: " + result);
    }

    @Test
    @DisplayName("编号标题断行 ### 2.\\n1 应合并为 ### 2.1")
    void shouldFixSplitNumberedHeading() {
        String raw = "### 2.\n\n1作用与原理\n\n内容正文";
        String result = MarkdownNormalizer.normalize(raw);
        assertTrue(result.contains("2.1") || result.contains("2. 1"),
                "编号标题应合并: " + result);
    }

    @Test
    @DisplayName("代码块内的转义字符不应被修改")
    void shouldPreserveEscapesInsideCodeBlock() {
        String raw = "正文 \\*\\*粗体\\*\\*\n\n```java\nString s = \"\\*not bold\\*\";\n```\n\n结尾";
        String result = MarkdownNormalizer.normalize(raw);
        assertTrue(result.contains("**粗体**"), "代码块外转义应修复: " + result);
        assertTrue(result.contains("\\*not bold\\*"), "代码块内转义应保留: " + result);
    }

    @Test
    @DisplayName("回归：B 类畸形——标题全部粘连在一行（无换行无标点）")
    void shouldSplitConcatenatedHeadings() {
        // 模拟真实 LLM 产出：所有标题粘在一行（用户报告的实际畸形样本）
        String raw = "##一、概述###1.1什么是 JVM内存结构###1.2为什么理解内存结构很重要##二、线程私有区域###2.1程序计数器";
        String result = MarkdownNormalizer.normalize(raw);
        // 每个 ## / ### 标题应当独占一行
        assertTrue(result.contains("\n"), "应被拆分成多行: " + result);
        // 标题后应有空格
        assertTrue(result.contains("## 一、概述") || result.contains("# 一、概述"),
                "第一个标题应规范化: " + result);
        assertTrue(result.contains("### 1.1") || result.contains("## 1.1"),
                "子标题应规范化: " + result);
        // 不应把所有内容挤在一个段落
        String[] lines = result.split("\n");
        assertTrue(lines.length >= 4, "至少应拆为 4+ 行: 实际 " + lines.length + " 行\n" + result);
    }

    @Test
    @DisplayName("PLAIN_TEXT 应去掉摘要标题和 Markdown 标记")
    void shouldNormalizePlainTextPolicy() {
        String raw = "## 摘要\n\n本文介绍 RocketMQ 的可靠投递机制。";
        String result = MarkdownNormalizer.normalize(raw, MarkdownPolicyVO.PLAIN_TEXT);
        assertEquals("本文介绍 RocketMQ 的可靠投递机制。", result);
    }

    @Test
    @DisplayName("PLAIN_LINES 应清理标题候选的编号和 Markdown 标记")
    void shouldNormalizePlainLinesPolicy() {
        String raw = "1. # 多 Agent 写作系统实践\n2. 从同步生成到异步任务";
        String result = MarkdownNormalizer.normalize(raw, MarkdownPolicyVO.PLAIN_LINES);
        assertEquals("多 Agent 写作系统实践\n从同步生成到异步任务", result);
    }

    @Test
    @DisplayName("TAGS 应支持多分隔符切分、去重并清理标记")
    void shouldNormalizeTagsPolicy() {
        String raw = "#Java、Spring Boot，RocketMQ\nRedis, Java";
        String result = MarkdownNormalizer.normalize(raw, MarkdownPolicyVO.TAGS);
        assertEquals("Java, Spring Boot, RocketMQ, Redis", result);
    }

    @Test
    @DisplayName("ARTICLE_LIGHT 不应执行标题层级强修正")
    void shouldKeepHeadingLevelForArticleLightPolicy() {
        String raw = "## 2.1 任务可靠投递";
        String result = MarkdownNormalizer.normalize(raw, MarkdownPolicyVO.ARTICLE_LIGHT);
        assertEquals("## 2.1 任务可靠投递", result);
    }

    @Test
    @DisplayName("代码块语言标识与代码粘连应被拆行")
    void shouldSplitCodeFenceLangFromCode() {
        String raw = "```javapublic void execute() {}\n```";
        String result = MarkdownNormalizer.normalize(raw, MarkdownPolicyVO.ARTICLE_STRICT);
        assertTrue(result.contains("```java\npublic"), "语言标识后应换行: " + result);
        assertFalse(result.contains("```javapublic"), "不应残留粘连: " + result);
    }

    @Test
    @DisplayName("标题与正文粘连不应把标题词切碎或孤立 ### 标记")
    void shouldNotCorruptGluedHeading() {
        String raw = "##一、引言###1.1微服务架构背景2010年前后每个请求都需要异步处理多个子任务并复用线程";
        String result = MarkdownNormalizer.normalize(raw, MarkdownPolicyVO.ARTICLE_STRICT);
        // ## 应补空格并与后续标题分行
        assertTrue(result.contains("## 一、引言"), "一级标题应规范化: " + result);
        // 不应出现孤立的空标题标记「### 」独占一行
        assertFalse(result.matches("(?s).*(^|\\n)#{2,6}\\s*\\n.*"), "不应产生空标题: " + result);
        // 不应把「异步处理」切成「异步处 / 理」
        assertFalse(result.contains("异步处\n"), "不应切在词中间: " + result);
    }

    @Test
    @DisplayName("普通合法长标题不应被误拆")
    void shouldNotSplitNormalHeading() {
        String raw = "### 一、概述";
        String result = MarkdownNormalizer.normalize(raw, MarkdownPolicyVO.ARTICLE_STRICT);
        assertTrue(result.startsWith("### 一、概述"), "合法标题应保持: " + result);
    }
}
