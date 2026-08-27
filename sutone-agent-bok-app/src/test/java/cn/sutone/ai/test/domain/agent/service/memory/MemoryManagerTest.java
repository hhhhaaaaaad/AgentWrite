package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.model.valobj.MemoryRetrieveQueryVO;
import cn.sutone.ai.domain.agent.service.memory.MemoryManager;
import cn.sutone.ai.domain.agent.service.memory.MemoryRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MemoryManager 单元测试")
@ExtendWith(MockitoExtension.class)
class MemoryManagerTest {

    @Mock
    private MemoryRetriever memoryRetriever;

    private MemoryManager memoryManager;

    @BeforeEach
    void setUp() throws Exception {
        memoryManager = new MemoryManager();

        var retrieverField = MemoryManager.class.getDeclaredField("memoryRetriever");
        retrieverField.setAccessible(true);
        retrieverField.set(memoryManager, memoryRetriever);

        var injectEnabledField = MemoryManager.class.getDeclaredField("injectEnabled");
        injectEnabledField.setAccessible(true);
        injectEnabledField.set(memoryManager, true);
    }

    @Test
    @DisplayName("旧字符串入口遇到空查询时应直接返回空字符串")
    void shouldShortCircuitBlankLegacyQuery() {
        assertEquals("", memoryManager.retrieveContext(1L, "", 5));
        assertEquals("", memoryManager.retrieveContext(1L, "   ", 5));
        assertEquals("", memoryManager.retrieveContext(1L, (String) null, 5));

        verify(memoryRetriever, never()).retrieveFormattedContext(anyLong(), anyString(), anyInt());
        verify(memoryRetriever, never()).retrieveFormattedContext(anyLong(), any(MemoryRetrieveQueryVO.class), anyInt());
    }

    @Test
    @DisplayName("旧字符串入口非空时应走 legacy 字符串检索链路")
    void shouldUseLegacyStringRetrieverForNonBlankQuery() {
        when(memoryRetriever.retrieveFormattedContext(1L, "Java 记忆系统", 5)).thenReturn("- 用户擅长 Java");

        String result = memoryManager.retrieveContext(1L, "Java 记忆系统", 5);

        assertEquals("- 用户擅长 Java", result);
        verify(memoryRetriever).retrieveFormattedContext(1L, "Java 记忆系统", 5);
        verify(memoryRetriever, never()).retrieveFormattedContext(anyLong(), any(MemoryRetrieveQueryVO.class), anyInt());
    }

    @Test
    @DisplayName("结构化入口遇到空查询对象时应直接返回空字符串")
    void shouldShortCircuitNullStructuredQuery() {
        assertEquals("", memoryManager.retrieveContext(1L, (MemoryRetrieveQueryVO) null, 5));

        verify(memoryRetriever, never()).retrieveFormattedContext(anyLong(), any(MemoryRetrieveQueryVO.class), anyInt());
    }
}
