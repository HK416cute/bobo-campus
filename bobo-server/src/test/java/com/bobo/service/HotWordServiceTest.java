package com.bobo.service;

import com.bobo.domain.entity.SearchLog;
import com.bobo.mapper.SearchLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 热门搜索词：真实日志优先、无数据/表异常时回退默认词、写入失败不影响搜索。
 *
 * @author bobo
 */
class HotWordServiceTest {

    private final SearchLogMapper mapper = mock(SearchLogMapper.class);
    private final HotWordService service = new HotWordService(mapper);

    @AfterEach
    void tearDown() {
        com.bobo.security.AuthContext.clear();
    }

    @Test
    @DisplayName("有真实搜索日志时按统计结果返回")
    void usesRealSearchStats() {
        when(mapper.selectHotKeywords(anyInt(), anyInt())).thenReturn(List.of(
                Map.of("keyword", "蓝牙耳机", "cnt", 12L),
                Map.of("keyword", "考研资料", "cnt", 8L)));

        assertThat(service.hotWords(2)).containsExactly("蓝牙耳机", "考研资料");
    }

    @Test
    @DisplayName("没有日志或表不存在时回退默认词")
    void fallsBackToDefaults() {
        when(mapper.selectHotKeywords(anyInt(), anyInt())).thenReturn(List.of());
        assertThat(service.hotWords(3)).containsExactly("考研资料", "蓝牙耳机", "宿舍小冰箱");

        when(mapper.selectHotKeywords(anyInt(), anyInt())).thenThrow(new RuntimeException("table not found"));
        HotWordService another = new HotWordService(mapper);
        assertThat(another.hotWords(2)).containsExactly("考研资料", "蓝牙耳机");
    }

    @Test
    @DisplayName("关键词归一化：去空白、过短丢弃、过长截断")
    void normalizeKeyword() {
        assertThat(service.normalize("  蓝牙   耳机 ")).isEqualTo("蓝牙 耳机");
        assertThat(service.normalize("a")).isNull();
        assertThat(service.normalize("   ")).isNull();
        assertThat(service.normalize("x".repeat(80))).hasSize(64);
    }

    @Test
    @DisplayName("记录搜索时写入归一化关键词；空关键词不写")
    void recordNormalizedKeyword() {
        service.record("  机械   键盘 ", "search");
        ArgumentCaptor<SearchLog> captor = ArgumentCaptor.forClass(SearchLog.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getKeyword()).isEqualTo("机械 键盘");
        assertThat(captor.getValue().getSource()).isEqualTo("search");

        service.record(" ", "search");
        verify(mapper).insert(any(SearchLog.class));
    }
}
