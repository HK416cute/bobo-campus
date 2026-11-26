package com.bobo.service;

import com.bobo.domain.entity.SearchLog;
import com.bobo.mapper.SearchLogMapper;
import com.bobo.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 热门搜索词：从真实搜索日志统计，无数据时回退到静态默认词。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>只统计最近 30 天</b>：热门词要反映当前需求，不能把半年前的词一直挂在首页；</li>
 *   <li><b>缓存 60 秒</b>：首页访问频率高，但热门词不需要实时到秒；缓存能避免每次刷新都扫日志表；</li>
 *   <li><b>表不存在/查询失败时回退</b>：新部署还没执行 07 脚本时首页仍能正常展示；</li>
 *   <li><b>日志写入失败不影响搜索</b>：热搜是增强功能，不能反过来拖垮搜索主链路。</li>
 * </ul>
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotWordService {

    /** 统计窗口：最近 30 天 */
    private static final int WINDOW_DAYS = 30;

    /** 缓存有效期：60 秒 */
    private static final long CACHE_MILLIS = 60_000L;

    /** 没有真实搜索日志时的默认热门词 */
    private static final List<String> DEFAULT_WORDS =
            List.of("考研资料", "蓝牙耳机", "宿舍小冰箱", "山地车", "机械键盘");

    private final SearchLogMapper searchLogMapper;

    private volatile Cache cache;

    /**
     * 取热门搜索词。
     *
     * @param limit 最多返回几个，服务端限制在 1~10
     */
    public List<String> hotWords(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 10));
        Cache local = cache;
        long now = System.currentTimeMillis();
        if (local != null && local.expireAt() > now) {
            return local.words().subList(0, Math.min(safeLimit, local.words().size()));
        }

        List<String> words = queryHotWords(safeLimit);
        cache = new Cache(words, now + CACHE_MILLIS);
        return words;
    }

    /**
     * 记录一次搜索。关键词为空、过短或写入失败时静默跳过。
     *
     * <p>由 Controller 在搜索第一页调用；这里不抛异常，避免日志表故障影响搜索。
     */
    public void record(String keyword, String source) {
        String normalized = normalize(keyword);
        if (normalized == null) {
            return;
        }
        try {
            SearchLog log = new SearchLog();
            log.setKeyword(normalized);
            log.setUserId(AuthContext.userIdOrNull());
            log.setSource(StringUtils.hasText(source) ? source : "search");
            log.setCreateTime(LocalDateTime.now());
            searchLogMapper.insert(log);
        } catch (Exception e) {
            // 未执行 07-search-log.sql 或数据库抖动：只记 warn，不影响搜索
            log.warn("写入搜索日志失败：keyword={}, reason={}", normalized, e.getMessage());
        }
    }

    /** 归一化：去首尾空白、合并连续空白，长度限制 2~64 */
    String normalize(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }
        String value = keyword.trim().replaceAll("\\s+", " ");
        if (value.length() < 2) {
            return null;
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private List<String> queryHotWords(int limit) {
        try {
            List<Map<String, Object>> rows = searchLogMapper.selectHotKeywords(WINDOW_DAYS, limit);
            if (rows == null || rows.isEmpty()) {
                return defaultWords(limit);
            }
            List<String> words = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Object keyword = row.get("keyword");
                if (keyword != null && StringUtils.hasText(keyword.toString())) {
                    words.add(keyword.toString());
                }
            }
            if (words.isEmpty()) {
                return defaultWords(limit);
            }
            // 真实搜索词优先；不足时用默认词补齐，避免上线初期首页只剩一个链接。
            for (String fallback : DEFAULT_WORDS) {
                if (words.size() >= limit) {
                    break;
                }
                if (!words.contains(fallback)) {
                    words.add(fallback);
                }
            }
            return words;
        } catch (Exception e) {
            log.debug("热门搜索统计失败，使用默认词：{}", e.getMessage());
            return defaultWords(limit);
        }
    }

    private List<String> defaultWords(int limit) {
        return DEFAULT_WORDS.subList(0, Math.min(limit, DEFAULT_WORDS.size()));
    }

    /** 缓存快照 */
    private record Cache(List<String> words, long expireAt) {
    }
}
