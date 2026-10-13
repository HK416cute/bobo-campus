package com.bobo.controller;

import com.bobo.common.Result;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.security.Anonymous;
import com.bobo.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 分类接口（前台只读，管理端维护在 AdminCategoryController）。
 *
 * @author bobo
 */
@Tag(name = "02 分类")
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Anonymous
    @Operation(summary = "启用中的分类（含在售数量）")
    @GetMapping
    public Result<List<SimpleVos.CategoryVO>> list() {
        return Result.ok(categoryService.listEnabled());
    }

    @Anonymous
    @Operation(summary = "分类下拉选项")
    @GetMapping("/options")
    public Result<List<Map<String, Object>>> options() {
        return Result.ok(categoryService.options());
    }
}
