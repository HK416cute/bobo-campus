package com.bobo.controller;

import com.bobo.common.PageResult;
import com.bobo.common.Result;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.query.ItemQuery;
import com.bobo.domain.vo.ItemCardVO;
import com.bobo.domain.vo.ItemDetailVO;
import com.bobo.security.Anonymous;
import com.bobo.service.FavoriteService;
import com.bobo.service.ItemService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 商品接口（前台）。
 *
 * @author bobo
 */
@Tag(name = "03 商品")
@RestController
@RequestMapping("/api/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;
    private final FavoriteService favoriteService;

    @Anonymous
    @Operation(summary = "商品列表 / 搜索")
    @GetMapping
    public Result<PageResult<ItemCardVO>> page(ItemQuery query) {
        return Result.ok(itemService.page(query));
    }

    @Anonymous
    @Operation(summary = "商品详情")
    @GetMapping("/{itemId}")
    public Result<ItemDetailVO> detail(@PathVariable Long itemId) {
        return Result.ok(itemService.detail(itemId));
    }

    @Operation(summary = "发布商品")
    @PostMapping
    public Result<Map<String, Object>> publish(@Valid @RequestBody Dtos.ItemDTO dto) {
        Long itemId = itemService.publish(dto);
        return Result.ok("发布成功，等待管理员审核", Map.of("itemId", itemId));
    }

    @Operation(summary = "编辑商品")
    @PutMapping("/{itemId}")
    public Result<Void> update(@PathVariable Long itemId, @Valid @RequestBody Dtos.ItemDTO dto) {
        itemService.update(itemId, dto);
        return Result.ok("已保存，修改后的商品需要重新审核", null);
    }

    @Operation(summary = "删除商品")
    @DeleteMapping("/{itemId}")
    public Result<Void> delete(@PathVariable Long itemId) {
        itemService.delete(itemId);
        return Result.ok("商品已删除", null);
    }

    @Operation(summary = "下架自己的商品")
    @PutMapping("/{itemId}/off-shelf")
    public Result<Void> offShelf(@PathVariable Long itemId) {
        itemService.offShelfByOwner(itemId);
        return Result.ok("已下架", null);
    }

    @Operation(summary = "重新上架（需重新审核）")
    @PutMapping("/{itemId}/on-shelf")
    public Result<Void> onShelf(@PathVariable Long itemId) {
        itemService.onShelfByOwner(itemId);
        return Result.ok("已提交上架申请，等待审核", null);
    }
}
