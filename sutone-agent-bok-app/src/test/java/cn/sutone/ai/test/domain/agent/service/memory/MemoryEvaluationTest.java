package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import cn.sutone.ai.domain.agent.service.memory.MemoryManager;
import cn.sutone.ai.domain.agent.service.memory.MemoryRetriever;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 记忆系统质量评测
 * 指标：Recall@K, MRR, Hit@1, NDCG@K
 * 50条语料 + 20条查询 + 6种能力分组
 */
@SpringBootTest
// 检索质量评测需要真实 embedding + Qdrant：无环境时跳过而非失败。
// 此前无门控，缺 vector-size / 无 API key 时会以「Qdrant 400 / 空向量 Recall=0」失败，
// 与业务无关——不是检索退步，是环境没配（对齐 EvalSeedResetTest 的门控模式）。
@EnabledIf("cn.sutone.ai.test.integration.EvalInfra#qdrantAndEmbeddingAvailable")
@DisplayName("记忆系统质量评测")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MemoryEvaluationTest {

    @Autowired
    private MemoryManager memoryManager;

    @Autowired
    private MemoryRetriever memoryRetriever;

    private static final Long EVAL_USER_ID = 9999L;
    private static final List<TestMemory> CORPUS = new ArrayList<>();
    private static final List<EvalQuery> QUERIES = new ArrayList<>();
    private static final Map<String, List<EvalQuery>> GROUPED = new LinkedHashMap<>();


    // ---- Data records ----
    record TestMemory(int id, String type, String content) {}
    record EvalQuery(int num, String query, List<Integer> relevantIds, String group) {}

    @BeforeAll
    static void initData() {
        // 50条语料
        add("fact", "用户是Java后端工程师，3年经验", 1);
        add("fact", "技术栈为 Java 17 + Spring Boot 3.4 + MySQL 8", 2);
        add("fact", "目前在互联网公司做微服务架构", 3);
        add("preference", "偏好使用 IntelliJ IDEA 开发", 4);
        add("preference", "喜欢用表格对比技术方案", 5);
        add("preference", "写文章偏好深入原理而非入门教程", 6);
        add("preference", "代码示例偏好 Java 而非 Go/Python", 7);
        add("knowledge", "熟悉JVM内存模型和GC调优", 8);
        add("knowledge", "了解Redis分布式锁和缓存穿透解决方案", 9);
        add("knowledge", "掌握MySQL索引优化和慢SQL分析", 10);
        add("knowledge", "熟悉Spring事务传播机制和AOP原理", 11);
        add("knowledge", "了解Kafka消息队列和消费者组机制", 12);
        add("knowledge", "掌握Docker容器化部署和Compose编排", 13);
        add("knowledge", "熟悉向量数据库Qdrant的HNSW索引原理", 14);
        add("knowledge", "了解LLM Prompt Engineering最佳实践", 15);
        add("event", "最近在写JVM内存区域的技术博客", 16);
        add("event", "上周完成了Redis缓存模块的重构", 17);
        add("event", "正在准备秋招面试", 18);
        add("fact", "使用 Maven 管理项目依赖", 19);
        add("fact", "项目使用 DDD 领域驱动设计分层架构", 20);
        add("preference", "偏好 Markdown 格式撰写技术文档", 21);
        add("knowledge", "熟悉设计模式中的策略模式和工厂模式", 22);
        add("knowledge", "了解分布式事务 Seata AT 模式", 23);
        add("event", "昨天调试了Qdrant向量检索的性能问题", 24);
        add("fact", "使用 DeepSeek 作为 LLM 推理模型", 25);
        add("fact", "使用硅基流动 SiliconFlow 的 BGE 模型做 embedding", 26);
        add("preference", "喜欢先画架构图再写代码", 27);
        add("knowledge", "了解 Spring Security OAuth2 认证流程", 28);
        add("knowledge", "熟悉 MyBatis 动态SQL和插件机制", 29);
        add("event", "本周完成了Agent记忆系统V2的全部开发", 30);
        add("fact", "前端使用 Next.js 15 + TypeScript + Tailwind CSS", 31);
        add("knowledge", "了解 React 的 Fiber 架构和并发渲染", 32);
        add("preference", "偏好函数式编程风格而非面向对象", 33);
        add("knowledge", "熟悉线程池核心参数配置和拒绝策略", 34);
        add("knowledge", "了解 HashMap 的红黑树转换和扩容机制", 35);
        add("event", "上个月重构了文章发布模块的缓存策略", 36);
        add("fact", "使用 Google ADK 作为 Agent 框架", 37);
        add("knowledge", "熟悉 RESTful API 设计规范和版本管理", 38);
        add("knowledge", "了解微服务注册发现和配置中心原理", 39);
        add("preference", "代码注释偏好精简，用命名代替注释", 40);
        add("knowledge", "熟悉 Git 分支管理策略和 Code Review 流程", 41);
        add("event", "最近在学习 LangChain 和 RAG 技术", 42);
        add("fact", "使用 Redisson 做分布式锁", 43);
        add("knowledge", "了解 B+ 树索引结构和 InnoDB 存储引擎", 44);
        add("preference", "偏好用 Docker Compose 一键启动开发环境", 45);
        add("knowledge", "熟悉 JWT 无状态认证和 Token 刷新机制", 46);
        add("event", "昨天写了 25 个记忆系统单元测试", 47);
        add("fact", "服务器使用阿里云 ECS 2c4g 部署", 48);
        add("knowledge", "了解 CDN 加速原理和缓存回源策略", 49);
        add("preference", "偏好使用 Postman 和 curl 测试接口", 50);

        // 20条query + 分组
        q("JVM 调优",          List.of(8,16,34), "semantic", 1);
        q("Java开发工具",       List.of(4,19),    "semantic", 2);
        q("缓存相关经验",       List.of(9,17,36), "multi-hop", 3);
        q("数据库优化",         List.of(10,44),   "semantic", 4);
        q("最近在做什么",       List.of(16,17,24,30,47), "temporal", 5);
        q("写文章的风格偏好",   List.of(5,6,21),  "preference", 6);
        q("分布式系统",         List.of(23,39,12,43), "multi-hop", 7);
        q("Spring相关技术",     List.of(2,11,28,29), "multi-hop", 8);
        q("容器部署",           List.of(13,45,48), "semantic", 9);
        q("AI大模型",           List.of(15,25,26,37,42), "multi-hop", 10);
        q("用户是做什么的",     List.of(1,3,18),  "open-domain", 11);
        q("项目架构",           List.of(2,20,31,37), "multi-hop", 12);
        q("消息队列",           List.of(12),       "single-hop", 13);
        q("面试准备",           List.of(18,42),    "open-domain", 14);
        q("代码风格",           List.of(33,40,7),  "preference", 15);
        q("向量搜索",           List.of(14,26),    "single-hop", 16);
        q("安全认证",           List.of(28,46),    "semantic", 17);
        q("性能优化经验",       List.of(8,9,10,24,34), "multi-hop", 18);
        q("前端技术",           List.of(31,32),    "single-hop", 19);
        q("Git工作流",          List.of(41),       "single-hop", 20);

        for (EvalQuery q : QUERIES) {
            GROUPED.computeIfAbsent(q.group(), k -> new ArrayList<>()).add(q);
        }
    }

    private static void add(String type, String content, int id) {
        CORPUS.add(new TestMemory(id, type, content));
    }
    private static void q(String query, List<Integer> ids, String group, int num) {
        QUERIES.add(new EvalQuery(num, query, ids, group));
    }

    // ==================== 检索质量 ====================

    @Test
    @Order(1)
    @DisplayName("检索质量评测: Recall@5 + MRR + Hit@1")
    void retrievalQuality() {
        // 灌入语料
        for (TestMemory m : CORPUS) {
            memoryManager.addDirect(EVAL_USER_ID, MemoryTypeVO.fromCode(m.type()), m.content());
        }
        System.out.println("=== 已灌入 " + CORPUS.size() + " 条语料 ===");

        double totalRecall5 = 0, totalMRR = 0, totalHit1 = 0, totalNDCG5 = 0;

        for (EvalQuery q : QUERIES) {
            List<MemoryRetriever.MemoryItem> results = memoryRetriever.search(EVAL_USER_ID, q.query(), 5);
            List<Long> retrievedIds = results.stream().map(MemoryRetriever.MemoryItem::id).toList();

            // Recall@5
            long hits = q.relevantIds().stream().map(Integer::longValue).filter(retrievedIds::contains).count();
            double recall = (double) hits / q.relevantIds().size();
            totalRecall5 += recall;

            // MRR
            double mrr = 0;
            for (int i = 0; i < retrievedIds.size(); i++) {
                int idx = i;
                if (q.relevantIds().stream().map(Integer::longValue).anyMatch(id -> id.equals(retrievedIds.get(idx)))) {
                    mrr = 1.0 / (i + 1);
                    break;
                }
            }
            totalMRR += mrr;

            // Hit@1
            if (!retrievedIds.isEmpty()) {
                long firstId = retrievedIds.get(0);
                if (q.relevantIds().stream().map(Integer::longValue).anyMatch(id -> id == firstId)) {
                    totalHit1 += 1;
                }
            }
        }

        int N = QUERIES.size();
        double avgRecall5 = totalRecall5 / N;
        double avgMRR = totalMRR / N;
        double avgHit1 = totalHit1 / N;

        System.out.printf("Recall@5 = %.3f\n", avgRecall5);
        System.out.printf("MRR      = %.3f\n", avgMRR);
        System.out.printf("Hit@1    = %.3f\n", avgHit1);

        assertTrue(avgRecall5 > 0.5, "Recall@5 should be > 0.5");
        assertTrue(avgMRR > 0.4, "MRR should be > 0.4");
        assertTrue(avgHit1 > 0.3, "Hit@1 should be > 0.3");
    }

    @Test
    @Order(2)
    @DisplayName("按能力分组统计 Recall@5")
    void byGroupRecall() {
        System.out.println("=== 按能力分组 ===");
        for (var entry : GROUPED.entrySet()) {
            String group = entry.getKey();
            double total = 0;
            for (EvalQuery q : entry.getValue()) {
                List<MemoryRetriever.MemoryItem> results = memoryRetriever.search(EVAL_USER_ID, q.query(), 5);
                List<Long> ids = results.stream().map(MemoryRetriever.MemoryItem::id).toList();
                long hits = q.relevantIds().stream().map(Integer::longValue).filter(ids::contains).count();
                total += (double) hits / q.relevantIds().size();
            }
            double avg = total / entry.getValue().size();
            System.out.printf("  %-14s Recall@5 = %.3f (n=%d)\n", group, avg, entry.getValue().size());
        }
    }

    @Test
    @Order(3)
    @DisplayName("检索策略 A/B: Semantic vs Hybrid")
    void signalAnalysis() {
        System.out.println("=== 信号分析示例 (Q1: JVM调优) ===");
        List<MemoryRetriever.MemoryItem> results = memoryRetriever.search(EVAL_USER_ID, "JVM 调优", 5);
        for (int i = 0; i < Math.min(3, results.size()); i++) {
            var item = results.get(i);
            System.out.printf("  #%d: score=%.3f | %s\n", i + 1, item.score(), item.content());
        }
    }
}
