package com.bobo.controller;

import com.bobo.common.Result;
import com.bobo.security.Anonymous;
import com.bobo.service.HotWordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 首页热门搜索接口。
 *
 * <p>匿名可读：首页未登录也会展示热门词。数据来自最近 30 天真实搜索日志，
 * 没有日志时返回默认词（考研资料、蓝牙耳机等）。
 *
 * @author bobo
 */
@Tag(name = "21 热门搜索")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class HotWordController {

    private final HotWordService hotWordService;

    @Anonymous
    @Operation(summary = "热门搜索词（最近 30 天真实搜索统计）")
    @GetMapping("/hot-words")
    public Result<List<String>> hotWords(@RequestParam(defaultValue = "8") int limit) {
        return Result.ok(hotWordService.hotWords(limit));
    }
}
