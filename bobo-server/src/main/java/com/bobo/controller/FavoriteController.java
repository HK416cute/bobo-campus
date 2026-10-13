package com.bobo.controller;

import com.bobo.common.PageResult;
import com.bobo.common.Result;
import com.bobo.domain.query.ItemQuery;
import com.bobo.domain.vo.ItemCardVO;
import com.bobo.service.FavoriteService;
import com.bobo.service.UploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 收藏（想要）与图片上传接口。
 *
 * @author bobo
 */
@Tag(name = "04 收藏与上传")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;
    private final UploadService uploadService;

    @Operation(summary = "收藏 / 取消收藏（切换）")
    @PostMapping("/favorites/toggle/{itemId}")
    public Result<Map<String, Object>> toggle(@PathVariable Long itemId) {
        return Result.ok(favoriteService.toggle(itemId));
    }

    @Operation(summary = "我的收藏分页")
    @GetMapping("/favorites")
    public Result<PageResult<ItemCardVO>> page(ItemQuery query) {
        return Result.ok(favoriteService.page(query));
    }

    @Operation(summary = "是否已收藏")
    @GetMapping("/favorites/check/{itemId}")
    public Result<Map<String, Object>> check(@PathVariable Long itemId) {
        return Result.ok(Map.of("favorited", favoriteService.isFavorited(itemId)));
    }

    @Operation(summary = "上传单张图片")
    @PostMapping("/upload/image")
    public Result<Map<String, Object>> uploadImage(@RequestParam("file") MultipartFile file) {
        return Result.ok(uploadService.upload(file));
    }

    @Operation(summary = "批量上传图片（最多 9 张）")
    @PostMapping("/upload/images")
    public Result<List<Map<String, Object>>> uploadImages(@RequestParam("files") List<MultipartFile> files) {
        return Result.ok(uploadService.uploadBatch(files));
    }
}
