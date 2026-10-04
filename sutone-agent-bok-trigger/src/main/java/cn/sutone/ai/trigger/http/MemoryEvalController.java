package cn.sutone.ai.trigger.http;

import cn.sutone.ai.api.dto.memory.EvalExtractRequestDTO;
import cn.sutone.ai.api.dto.memory.EvalFencingAcquireRequestDTO;
import cn.sutone.ai.api.dto.memory.EvalFencingReleaseRequestDTO;
import cn.sutone.ai.api.dto.memory.EvalGovernanceReplayRequestDTO;
import cn.sutone.ai.api.dto.memory.EvalGovernanceSamplesRequestDTO;
import cn.sutone.ai.api.dto.memory.EvalGovernanceSamplesResponseDTO;
import cn.sutone.ai.api.dto.memory.EvalMetricsResponseDTO;
import cn.sutone.ai.api.dto.memory.EvalParamsResponseDTO;
import cn.sutone.ai.api.dto.memory.EvalResetRequestDTO;
import cn.sutone.ai.api.dto.memory.EvalResetResponseDTO;
import cn.sutone.ai.api.dto.memory.EvalRetrieveContextRequestDTO;
import cn.sutone.ai.api.dto.memory.EvalRetrieveContextResponseDTO;
import cn.sutone.ai.api.dto.memory.EvalSearchRequestDTO;
import cn.sutone.ai.api.dto.memory.EvalSearchResponseDTO;
import cn.sutone.ai.api.dto.memory.EvalParamOverridesDTO;
import cn.sutone.ai.api.dto.memory.EvalSeedRequestDTO;
import cn.sutone.ai.api.dto.memory.EvalSeedResponseDTO;
import cn.sutone.ai.api.response.Response;
import cn.sutone.ai.domain.agent.adapter.repository.IEvalFencingRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryMetricsPort;
import cn.sutone.ai.domain.agent.model.exception.MemoryEvalFencingException;
import cn.sutone.ai.domain.agent.model.valobj.EvalParamOverrides;
import cn.sutone.ai.domain.agent.model.valobj.GovernanceDecision;
import cn.sutone.ai.domain.agent.model.valobj.MemoryCandidate;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import cn.sutone.ai.domain.agent.model.valobj.RetrieverParams;
import cn.sutone.ai.domain.agent.model.valobj.properties.MemoryProperties;
import cn.sutone.ai.domain.agent.service.memory.MemoryEvalGuardService;
import cn.sutone.ai.domain.agent.service.memory.MemoryManager;
import cn.sutone.ai.domain.agent.service.memory.MemoryRetriever;
import cn.sutone.ai.domain.agent.service.memory.circuit.MemoryCircuitBreaker;
import cn.sutone.ai.domain.agent.service.memory.governance.MemoryGovernanceComputeService;
import cn.sutone.ai.types.enums.ResponseCode;
import cn.sutone.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 记忆系统评测端点（供独立评测平台调用）。
 *
 * <p>安全边界：所有端点要求 {@code ROLE_EVAL}（见 {@code SecurityConfig}），客户端仅能传入
 * 落在 {@code [baseUserId, baseUserId+userIdRange)} 内的 eval_user_id，禁止越过评测命名空间
 * 直接访问业务 userId（IDOR 防护）。</p>
 *
 * <p>破坏性操作（reset/seed）经 {@link MemoryEvalGuardService} 守卫：fencing 校验与 MySQL 写在
 * 同一持锁事务内完成，需携带 {@code X-Eval-Run-Id}（必填）与 {@code X-Eval-Fencing}（可选，传入则校验版本）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/eval")
public class MemoryEvalController {

    @Resource
    private MemoryManager memoryManager;

    @Resource
    private MemoryProperties memoryProperties;

    @Resource
    private IEvalFencingRepository evalFencingRepository;

    @Resource
    private IMemoryMetricsPort metrics;

    @Resource
    private MemoryCircuitBreaker circuitBreaker;

    @Resource
    private MemoryEvalGuardService evalGuardService;

    @Resource
    private MemoryGovernanceComputeService governanceComputeService;

    /** 校验 eval_user_id 落在评测命名空间，越界抛 EVAL_FORBIDDEN（IDOR 防护） */
    private void validateEvalUserId(Long evalUserId) {
        MemoryProperties.Eval eval = memoryProperties.getEval();
        long base = eval.getBaseUserId();
        long range = eval.getUserIdRange();
        if (evalUserId == null || evalUserId < base || evalUserId >= base + range) {
            throw new AppException(ResponseCode.EVAL_FORBIDDEN.getCode(),
                    "eval_user_id 越界: " + evalUserId);
        }
    }

    /**
     * DTO → 领域覆盖对象。{@code null} 与「全字段为空」都归一到
     * {@link EvalParamOverrides#NONE}，让下游只需判断一种「没有覆盖」的表示。
     */
    private EvalParamOverrides toOverrides(EvalParamOverridesDTO dto) {
        if (dto == null) {
            return EvalParamOverrides.NONE;
        }
        return new EvalParamOverrides(
                dto.getRrfK(), dto.getAlpha(), dto.getBeta(), dto.getRecencyHalfLifeDays(),
                dto.getProfileBoost(), dto.getMinConfidence(), dto.getInjectMaxTokens());
    }

    /**
     * 幂等写入种子语料。
     *
     * <p>同一 (eval_user_id, content) 重复 seed 不会产生重复行——撞 uk_user_hash 唯一索引
     * 转 existed，返回已有 createdId。连续 seed 不抛异常。</p>
     */
    @PostMapping("/seed")
    public Response<EvalSeedResponseDTO> seed(@RequestBody EvalSeedRequestDTO request,
                                              @RequestHeader("X-Eval-Run-Id") String runId,
                                              @RequestHeader(value = "X-Eval-Fencing", required = false) Long fencingVersion) {
        try {
            Long evalUserId = request.getEvalUserId();
            validateEvalUserId(evalUserId);
            if (request.getItems() == null || request.getItems().isEmpty()) {
                return Response.<EvalSeedResponseDTO>builder()
                        .code(ResponseCode.EVAL_INVALID.getCode())
                        .info(ResponseCode.EVAL_INVALID.getInfo())
                        .build();
            }

            List<MemoryEvalGuardService.SeedItem> items = new ArrayList<>();
            for (EvalSeedRequestDTO.Item item : request.getItems()) {
                if (item.getType() == null || item.getContent() == null || item.getContent().isBlank()) {
                    continue;
                }
                items.add(new MemoryEvalGuardService.SeedItem(
                        MemoryTypeVO.fromCode(item.getType()), item.getContent(),
                        item.getSubject(), item.getPredicate(), item.getValue(),
                        item.getConfidence(), item.getExpireTime()));
            }

            MemoryEvalGuardService.SeedOutcome outcome =
                    evalGuardService.seedGuarded(evalUserId, runId, fencingVersion, items);

            return Response.<EvalSeedResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(EvalSeedResponseDTO.builder()
                            .inserted(outcome.inserted())
                            .existed(outcome.existed())
                            .contentToId(outcome.contentToId())
                            .build())
                    .build();
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("eval seed 失败", e);
            return fail(e);
        }
    }

    /**
     * 清空某评测命名空间（物理删除 MySQL 记录 + 清空向量），幂等。
     *
     * <p>uk_user_hash 唯一索引不区分软删状态，故必须物理删除才能释放唯一键、允许重新 seed 相同内容。</p>
     */
    @PostMapping("/reset")
    public Response<EvalResetResponseDTO> reset(@RequestBody EvalResetRequestDTO request,
                                                @RequestHeader("X-Eval-Run-Id") String runId,
                                                @RequestHeader(value = "X-Eval-Fencing", required = false) Long fencingVersion) {
        try {
            Long evalUserId = request.getEvalUserId();
            validateEvalUserId(evalUserId);
            int mysqlDeleted = evalGuardService.resetGuarded(evalUserId, runId, fencingVersion);
            return Response.<EvalResetResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(EvalResetResponseDTO.builder()
                            .mysqlDeleted(mysqlDeleted)
                            .vectorCleared(true)
                            .build())
                    .build();
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("eval reset 失败", e);
            return fail(e);
        }
    }

    /**
     * 评测检索：返回 content 供 contentHash 匹配，支持 freezeSideEffects / exact / hnsw_ef。
     */
    @PostMapping("/search")
    public Response<EvalSearchResponseDTO> search(@RequestBody EvalSearchRequestDTO request) {
        try {
            Long evalUserId = request.getEvalUserId();
            validateEvalUserId(evalUserId);
            if (request.getQuery() == null || request.getQuery().isBlank()) {
                return Response.<EvalSearchResponseDTO>builder()
                        .code(ResponseCode.EVAL_INVALID.getCode())
                        .info(ResponseCode.EVAL_INVALID.getInfo())
                        .build();
            }
            int topK = request.getTopK() > 0 ? request.getTopK() : 5;
            double threshold = request.getThreshold() != null ? request.getThreshold() : 0.1;
            List<MemoryRetriever.MemoryItem> items = memoryManager.searchForEval(
                    evalUserId, request.getQuery(), topK, threshold,
                    request.isFreezeSideEffects(), request.getExact(), request.getHnswEf(),
                    toOverrides(request.getOverrides()));
            List<EvalSearchResponseDTO.Item> dtos = items.stream()
                    .map(m -> EvalSearchResponseDTO.Item.builder()
                            .id(m.id())
                            .content(m.content())
                            .score(m.score())
                            .importance(m.importance())
                            .type(m.type() != null ? m.type().getCode() : null)
                            .confidence(m.confidence())
                            .build())
                    .toList();
            return Response.<EvalSearchResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(EvalSearchResponseDTO.builder().items(dtos).build())
                    .build();
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("eval search 失败", e);
            return fail(e);
        }
    }

    /**
     * 评测抽取：对给定消息做 LLM 抽取，仅返回候选列表，不落库。
     */
    @PostMapping("/extract")
    public Response<List<MemoryCandidate>> extract(@RequestBody EvalExtractRequestDTO request) {
        try {
            Long evalUserId = request.getEvalUserId();
            validateEvalUserId(evalUserId);
            if (request.getMessages() == null || request.getMessages().isEmpty()) {
                return Response.<List<MemoryCandidate>>builder()
                        .code(ResponseCode.EVAL_INVALID.getCode())
                        .info(ResponseCode.EVAL_INVALID.getInfo())
                        .build();
            }
            List<MemoryCandidate> candidates = memoryManager.extractForEval(evalUserId, request.getMessages());
            return Response.<List<MemoryCandidate>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(candidates)
                    .build();
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("eval extract 失败", e);
            return fail(e);
        }
    }

    /**
     * 评测注入上下文：返回 budgeted 明细 + 格式化文本 + token 数。
     */
    @PostMapping("/retrieve-context")
    public Response<EvalRetrieveContextResponseDTO> retrieveContext(@RequestBody EvalRetrieveContextRequestDTO request) {
        try {
            Long evalUserId = request.getEvalUserId();
            validateEvalUserId(evalUserId);
            if (request.getQueryContext() == null || request.getQueryContext().isBlank()) {
                return Response.<EvalRetrieveContextResponseDTO>builder()
                        .code(ResponseCode.EVAL_INVALID.getCode())
                        .info(ResponseCode.EVAL_INVALID.getInfo())
                        .build();
            }
            int topK = request.getTopK() > 0 ? request.getTopK() : 5;
            MemoryRetriever.RetrieveContextResult r = memoryManager.retrieveContextForEval(
                    evalUserId, request.getQueryContext(), topK, toOverrides(request.getOverrides()));
            return Response.<EvalRetrieveContextResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(EvalRetrieveContextResponseDTO.builder()
                            .formatted(r.formatted())
                            .tokenCount(r.tokenCount())
                            .budgetedIds(r.budgeted().stream().map(MemoryRetriever.MemoryItem::id).toList())
                            .build())
                    .build();
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("eval retrieve-context 失败", e);
            return fail(e);
        }
    }

    /**
     * fencing 权威态哨兵：无行返回 {fencingVersion:0, activeRunId:null}。
     */
    @GetMapping("/fencing/{evalUserId}")
    public Response<IEvalFencingRepository.EvalFencingState> getFencing(@PathVariable("evalUserId") Long evalUserId) {
        try {
            validateEvalUserId(evalUserId);
            IEvalFencingRepository.EvalFencingState state = evalFencingRepository.get(evalUserId);
            return Response.<IEvalFencingRepository.EvalFencingState>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(state)
                    .build();
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("eval fencing get 失败", e);
            return fail(e);
        }
    }

    /**
     * 抢占 fencing：事务内读改写，冲突返回当前权威版本（alignment protocol）。
     */
    @PostMapping("/fencing/acquire")
    public Response<IEvalFencingRepository.AcquireResult> acquireFencing(@RequestBody EvalFencingAcquireRequestDTO request) {
        try {
            validateEvalUserId(request.getEvalUserId());
            IEvalFencingRepository.AcquireResult result = evalFencingRepository.acquire(
                    request.getEvalUserId(), request.getExpectedVersion(), request.getRunId());
            return Response.<IEvalFencingRepository.AcquireResult>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result)
                    .build();
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("eval fencing acquire 失败", e);
            return fail(e);
        }
    }

    /**
     * 释放 fencing：仅当 run_id 匹配 active_run_id 时清空（CAS）。
     */
    @PostMapping("/fencing/release")
    public Response<Boolean> releaseFencing(@RequestBody EvalFencingReleaseRequestDTO request) {
        try {
            validateEvalUserId(request.getEvalUserId());
            boolean released = evalFencingRepository.release(request.getEvalUserId(), request.getRunId());
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(released)
                    .build();
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("eval fencing release 失败", e);
            return fail(e);
        }
    }

    /**
     * 评测参数快照：供平台生成 config_fingerprint。
     */
    @GetMapping("/params")
    public Response<EvalParamsResponseDTO> params() {
        try {
            return Response.<EvalParamsResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toParamsDto(memoryManager.resolveEvalParams(EvalParamOverrides.NONE)))
                    .build();
        } catch (Exception e) {
            log.error("eval params 失败", e);
            return fail(e);
        }
    }

    /**
     * 参数**回声**：接收一份覆盖，返回解析后的生效参数。
     *
     * <p><b>为什么需要这个只读端点</b>：参数覆盖是一条「发出去可能被静默丢弃」的通道，
     * 而静默丢弃两侧都会发生：</p>
     * <ul>
     *   <li>平台下发了一个本 DTO 没有的键 → Spring Boot 默认
     *       {@code FAIL_ON_UNKNOWN_PROPERTIES=false}，Jackson **不报错、直接忽略**，
     *       参数变成不生效；</li>
     *   <li>本端 {@code /eval/params} 多了一个字段，平台侧的下发清单没跟上 →
     *       新参数只进 {@code config_fingerprint} 不进执行，即本次改动要消灭的那个缺陷。</li>
     * </ul>
     * <p>两种都不会抛异常，只会让「看起来是参数对照实验」的报告实际测的是同一套配置。
     * 有了回声，平台可以对**每一个**可下发键发一个带辨识度的值并断言回声里读得回来——
     * 通道被丢弃时立刻变红，而不是几个月后从一份解释不了的指标里反推。</p>
     *
     * <p>实现上刻意复用 {@link MemoryManager#resolveEvalParams}——与检索/注入同一入口，
     * 保证回声答的就是检索真正会用的那套参数。</p>
     */
    @PostMapping("/params/resolve")
    public Response<EvalParamsResponseDTO> resolveParams(
            @RequestBody(required = false) EvalParamOverridesDTO request) {
        try {
            return Response.<EvalParamsResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(toParamsDto(memoryManager.resolveEvalParams(toOverrides(request))))
                    .build();
        } catch (Exception e) {
            log.error("eval params resolve 失败", e);
            return fail(e);
        }
    }

    /**
     * 生效参数 → 响应 DTO。{@code /params} 与 {@code /params/resolve} **共用**这一份组装，
     * 避免两个端点各自拼字段、日后加参数时只改一处而让回声与快照对不上。
     *
     * <p>{@code vectorStore} 取自配置而不取自 {@link RetrieverParams}：它不在生效参数里
     * （选 collection 属部署期选择，不支持请求级覆盖），但仍要出现在快照中参与指纹。</p>
     */
    private EvalParamsResponseDTO toParamsDto(RetrieverParams effective) {
        return EvalParamsResponseDTO.builder()
                .vectorStore(memoryProperties.getVectorStore())
                .rrfK(effective.rrfK())
                .alpha(effective.alpha())
                .beta(effective.beta())
                .recencyHalfLifeDays(effective.recencyHalfLifeDays())
                .profileBoost(effective.profileBoost())
                .minConfidence(effective.minConfidence())
                .injectMaxTokens(effective.injectMaxTokens())
                .build();
    }

    /**
     * 评测观测指标：抽取驳回率 + 向量同步积压数。
     */
    @GetMapping("/metrics")
    public Response<EvalMetricsResponseDTO> metrics() {
        try {
            EvalMetricsResponseDTO dto = EvalMetricsResponseDTO.builder()
                    .extractionRejectRate(metrics.getExtractionRejectRate())
                    .vectorSyncPendingCount(metrics.getVectorSyncPendingCount())
                    // 分原因的驳回明细。注意是**进程内累计值**而不是本次 run 的——
                    // 平台要拿某次 run 的增量就得取前后两次的差，见 DTO 上的说明。
                    .extractionRejectedByReason(metrics.getExtractionRejectedByReason())
                    .build();
            return Response.<EvalMetricsResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dto)
                    .build();
        } catch (Exception e) {
            log.error("eval metrics 失败", e);
            return fail(e);
        }
    }

    /**
     * 评测熔断状态：注入是否已降级。
     */
    @GetMapping("/circuit-breaker")
    public Response<Boolean> circuitBreaker() {
        try {
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(circuitBreaker.isDegraded())
                    .build();
        } catch (Exception e) {
            log.error("eval circuit-breaker 失败", e);
            return fail(e);
        }
    }

    /**
     * 治理样本导出：四类治理任务的输入候选快照（只读），供平台与 ground truth 对账。
     *
     * <p><b>evalUserId 必须传给服务层。</b>此前这里只做了 {@code validateEvalUserId}
     * （校验落在命名空间区间内），却没有把它传下去——服务层的样本查询当时是全局的，
     * 于是「命名空间校验」形同虚设，接口实际返回全库所有用户的记忆内容。
     * 校验一个值却不使用它，比不校验更危险：它让人以为边界已经守住了。</p>
     */
    @PostMapping("/governance/samples")
    public Response<EvalGovernanceSamplesResponseDTO> governanceSamples(@RequestBody EvalGovernanceSamplesRequestDTO request) {
        try {
            validateEvalUserId(request.getEvalUserId());
            MemoryGovernanceComputeService.GovernanceSamples samples =
                    governanceComputeService.samples(request.getEvalUserId());
            return Response.<EvalGovernanceSamplesResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(EvalGovernanceSamplesResponseDTO.builder()
                            .duplicates(toBucket(samples.duplicates()))
                            .consistency(toBucket(samples.consistency()))
                            .expired(toBucket(samples.expired()))
                            .hallucination(toBucket(samples.hallucination()))
                            .build())
                    .build();
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("eval governance samples 失败", e);
            return fail(e);
        }
    }

    /**
     * 治理重放：<b>只调 compute 层，绝不落库</b>。
     *
     * <p>用于在评测环境验证治理规则是否误伤 / 误合并 / 误归档——同一批语料重放多次结果必须一致，
     * 且 memory_record 不被改动。</p>
     */
    @PostMapping("/governance/replay")
    public Response<List<GovernanceDecision>> governanceReplay(@RequestBody EvalGovernanceReplayRequestDTO request) {
        try {
            validateEvalUserId(request.getEvalUserId());
            List<GovernanceDecision> decisions = new ArrayList<>();
            if (request.isDuplicates()) {
                decisions.addAll(governanceComputeService.computeDuplicates());
            }
            if (request.isConsistency()) {
                // 恒为空：computeConsistency 在当前写入不变量下结构性不可达（见其 javadoc）。
                // 保留本分支，是为了让「请求 consistency 任务」得到**诚实的空结果**而不是报错。
                //
                // ⚠️ 但平台侧**不得**据此得出「一致性零问题」：期望空 + 实际空 会让治理评估器
                // 打出「零误判 / 零误伤」的满分，而实际上这项检查一次都没执行过。
                // 评测集因此已不含 consistency case（见 eval-platform 的
                // docs/governance-reachability.md）。若哪天本分支开始返回非空，
                // 说明不变量被放开了——那时才应该把该 case 加回评测集。
                decisions.addAll(governanceComputeService.computeConsistency());
            }
            if (request.isExpired()) {
                decisions.addAll(governanceComputeService.computeExpired());
            }
            if (request.isHallucination()) {
                decisions.addAll(governanceComputeService.computeHallucination());
            }
            return Response.<List<GovernanceDecision>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(decisions)
                    .build();
        } catch (AppException e) {
            return fail(e);
        } catch (Exception e) {
            log.error("eval governance replay 失败", e);
            return fail(e);
        }
    }

    /** compute 层样本桶 → 响应 DTO */
    private static EvalGovernanceSamplesResponseDTO.Bucket toBucket(
            MemoryGovernanceComputeService.GovernanceSampleBucket src) {
        List<EvalGovernanceSamplesResponseDTO.Item> items = src.items().stream()
                .map(i -> EvalGovernanceSamplesResponseDTO.Item.builder()
                        .memoryId(i.memoryId())
                        .userId(i.userId())
                        .type(i.type())
                        .content(i.content())
                        .status(i.status())
                        .subject(i.subject())
                        .predicate(i.predicate())
                        .value(i.value())
                        .confidence(i.confidence())
                        .build())
                .toList();
        return EvalGovernanceSamplesResponseDTO.Bucket.builder()
                .total(src.total())
                .items(items)
                .build();
    }

    private <T> Response<T> fail(Exception e) {
        String code = ResponseCode.UN_ERROR.getCode();
        String info = e.getMessage();
        if (e instanceof MemoryEvalFencingException) {
            // fencing 守卫失败：统一映射 403，不向调用方泄漏内部校验细节
            code = ResponseCode.EVAL_FORBIDDEN.getCode();
            info = ResponseCode.EVAL_FORBIDDEN.getInfo();
        } else if (e instanceof AppException ae) {
            code = ae.getCode() != null ? ae.getCode() : code;
            info = ae.getInfo() != null ? ae.getInfo() : info;
        }
        return Response.<T>builder().code(code).info(info).build();
    }
}
