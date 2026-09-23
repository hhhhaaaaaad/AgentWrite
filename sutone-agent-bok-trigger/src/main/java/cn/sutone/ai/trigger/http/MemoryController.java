package cn.sutone.ai.trigger.http;

import cn.sutone.ai.api.dto.memory.MemoryItemDTO;
import cn.sutone.ai.api.dto.memory.MemoryListResponseDTO;
import cn.sutone.ai.api.dto.memory.MemorySearchResponseDTO;
import cn.sutone.ai.api.response.Response;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.exception.MemoryAccessDeniedException;
import cn.sutone.ai.domain.agent.service.memory.MemoryManager;
import cn.sutone.ai.domain.agent.service.memory.MemoryRetriever;
import cn.sutone.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 记忆系统 REST API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/memory")
@CrossOrigin(origins = "*")
public class MemoryController {

    @Resource
    private MemoryManager memoryManager;

    /** 从 JWT 获取当前用户 ID */
    private Long getCurrentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof Long) {
            return (Long) principal;
        }
        return null;
    }

    /**
     * 语义搜索记忆
     */
    @GetMapping("/search")
    public Response<MemorySearchResponseDTO> search(@RequestParam("q") String query,
                                                     @RequestParam(value = "n", defaultValue = "5") int topK) {
        Long userId = getCurrentUserId();
        try {
            log.info("记忆搜索 userId={} query={} n={}", userId, query, topK);
            List<MemoryRetriever.MemoryItem> items = memoryManager.search(userId, query, topK);

            List<MemoryItemDTO> dtos = items.stream().map(item -> MemoryItemDTO.builder()
                    .id(item.id())
                    .content(item.content())
                    .score(item.score())
                    .importance(item.importance())
                    .build()).collect(Collectors.toList());

            MemorySearchResponseDTO data = MemorySearchResponseDTO.builder()
                    .query(query)
                    .items(dtos)
                    .total(dtos.size())
                    .build();

            return Response.<MemorySearchResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("记忆搜索失败 query={}: {}", query, e.getMessage(), e);
            return Response.<MemorySearchResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("搜索失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 分页记忆列表
     */
    @GetMapping("/list")
    public Response<MemoryListResponseDTO> list(@RequestParam(value = "page", defaultValue = "1") int page,
                                                 @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        Long userId = getCurrentUserId();
        try {
            log.info("记忆列表 userId={} page={} pageSize={}", userId, page, pageSize);
            List<MemoryRecordEntity> records = memoryManager.list(userId, page, pageSize);
            int total = memoryManager.count(userId);

            List<MemoryItemDTO> dtos = records.stream().map(r -> MemoryItemDTO.builder()
                    .id(r.getId())
                    .type(r.getType().getCode())
                    .content(r.getContent())
                    .importance(r.getImportance())
                    .accessCount(r.getAccessCount())
                    .createTime(r.getCreateTime() != null ? r.getCreateTime().toString() : null)
                    .build()).collect(Collectors.toList());

            MemoryListResponseDTO data = MemoryListResponseDTO.builder()
                    .items(dtos)
                    .page(page)
                    .pageSize(pageSize)
                    .total(total)
                    .build();

            return Response.<MemoryListResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.error("记忆列表查询失败 userId={}", userId, e);
            return Response.<MemoryListResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 单条记忆详情
     */
    @GetMapping("/{id}")
    public Response<MemoryItemDTO> detail(@PathVariable("id") Long id) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Response.<MemoryItemDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("用户未登录")
                    .build();
        }
        try {
            MemoryRecordEntity record = memoryManager.get(id);
            // 归属校验：不区分「不存在」与「无权访问」，防 ID 枚举
            if (record == null || !userId.equals(record.getUserId())) {
                return Response.<MemoryItemDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("记忆不存在或无权访问")
                        .build();
            }
            MemoryItemDTO dto = MemoryItemDTO.builder()
                    .id(record.getId())
                    .type(record.getType().getCode())
                    .content(record.getContent())
                    .importance(record.getImportance())
                    .accessCount(record.getAccessCount())
                    .createTime(record.getCreateTime() != null ? record.getCreateTime().toString() : null)
                    .build();

            return Response.<MemoryItemDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(dto)
                    .build();
        } catch (Exception e) {
            log.error("记忆详情查询失败 id={}", id, e);
            return Response.<MemoryItemDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 删除记忆
     */
    @DeleteMapping("/{id}")
    public Response<String> delete(@PathVariable("id") Long id) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Response.<String>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("用户未登录")
                    .build();
        }
        try {
            memoryManager.delete(userId, id);
            return Response.<String>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data("ok")
                    .build();
        } catch (MemoryAccessDeniedException e) {
            // 归属校验失败：不区分「不存在」与「无权访问」，防 ID 枚举
            return Response.<String>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("记忆不存在或无权访问")
                    .build();
        } catch (Exception e) {
            log.error("记忆删除失败 id={}", id, e);
            return Response.<String>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    /**
     * 全量迁移：MySQL 记忆 → Qdrant
     */
    @PostMapping("/migrate/all")
    public Response<Map<String, Object>> migrateAll(
            @RequestParam(value = "batchSize", defaultValue = "50") int batchSize,
            @RequestParam(value = "rateLimit", defaultValue = "5") int rateLimit) {
        try {
            log.info("全量迁移启动 batchSize={} rateLimit={}", batchSize, rateLimit);
            Map<String, Object> result = memoryManager.migrateAll(batchSize, rateLimit);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result)
                    .build();
        } catch (Exception e) {
            log.error("全量迁移失败", e);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("迁移失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 手动触发记忆重新抽取
     */
    @PostMapping("/refresh")
    public Response<String> refresh(@RequestParam("sessionId") String sessionId) {
        Long userId = getCurrentUserId();
        try {
            if (userId == null) {
                return Response.<String>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("userId 不能为空")
                        .build();
            }
            log.info("手动触发记忆抽取 userId={} sessionId={}", userId, sessionId);
            memoryManager.addAsync(userId, 0L, sessionId, List.of());
            return Response.<String>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data("已触发")
                    .build();
        } catch (Exception e) {
            log.error("手动触发记忆抽取失败 sessionId={}", sessionId, e);
            return Response.<String>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }
}
