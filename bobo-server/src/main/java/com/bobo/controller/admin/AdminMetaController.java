package com.bobo.controller.admin;

import com.bobo.common.PageResult;
import com.bobo.common.Result;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.service.CategoryService;
import com.bobo.service.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端基础数据维护：分类与公告。
 *
 * @author bobo
 */
@Tag(name = "25 管理端-分类与公告")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminMetaController {

    private final CategoryService categoryService;
    private final NoticeService noticeService;

    // ------------------------------------------------------------------ 分类

    @Operation(summary = "全部分类（含停用与商品数）")
    @GetMapping("/categories")
    public Result<List<SimpleVos.CategoryVO>> categories() {
        return Result.ok(categoryService.listAll());
    }

    @Operation(summary = "新增分类")
    @PostMapping("/categories")
    public Result<Map<String, Object>> createCategory(@Valid @RequestBody Dtos.CategoryDTO dto) {
        var category = categoryService.create(dto);
        return Result.ok("分类已新增", Map.of("categoryId", category.getCategoryId()));
    }

    @Operation(summary = "编辑分类")
    @PutMapping("/categories/{categoryId}")
    public Result<Void> updateCategory(@PathVariable Long categoryId,
                                       @Valid @RequestBody Dtos.CategoryDTO dto) {
        categoryService.update(categoryId, dto);
        return Result.ok("分类已更新", null);
    }

    @Operation(summary = "删除分类（分类下有商品时会被拒绝）")
    @DeleteMapping("/categories/{categoryId}")
    public Result<Void> deleteCategory(@PathVariable Long categoryId) {
        categoryService.delete(categoryId);
        return Result.ok("分类已删除", null);
    }

    // ------------------------------------------------------------------ 公告

    @Operation(summary = "公告分页")
    @GetMapping("/notices")
    public Result<PageResult<SimpleVos.NoticeVO>> notices(@RequestParam(required = false) String type,
                                                          @RequestParam(defaultValue = "1") long pageNum,
                                                          @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(noticeService.adminPage(type, pageNum, pageSize));
    }

    @Operation(summary = "发布公告 / 给指定用户发通知（userId=0 为全站）")
    @PostMapping("/notices")
    public Result<Map<String, Object>> publishNotice(@Valid @RequestBody Dtos.NoticeDTO dto) {
        Long noticeId = noticeService.publish(dto);
        return Result.ok("已发布", Map.of("noticeId", noticeId));
    }

    @Operation(summary = "删除公告")
    @DeleteMapping("/notices/{noticeId}")
    public Result<Void> deleteNotice(@PathVariable Long noticeId) {
        noticeService.delete(noticeId);
        return Result.ok("已删除", null);
    }
}
