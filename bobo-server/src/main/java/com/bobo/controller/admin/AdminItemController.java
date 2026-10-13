package com.bobo.controller.admin;

import com.bobo.common.PageResult;
import com.bobo.common.Result;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.query.ItemQuery;
import com.bobo.domain.vo.ItemCardVO;
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
 * 管理端商品管理：审核、上下架、批量操作。
 * <p>
 * 鉴权由 {@code JwtInterceptor} 对 /api/admin/** 统一把关，这里不再重复判断。
 *
 * @author bobo
 */
@Tag(name = "21 管理端-商品")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminItemController {

    private final ItemService itemService;

    @Operation(summary = "商品管理分页（不传 status 为全部）")
    @GetMapping("/items")
    public Result<PageResult<ItemCardVO>> page(ItemQuery query) {
        return Result.ok(itemService.page(query));
    }

    @Operation(summary = "审核商品")
    @PostMapping("/items/{itemId}/audit")
    public Result<Void> audit(@PathVariable Long itemId, @Valid @RequestBody Dtos.AuditDTO dto) {
        itemService.audit(itemId, dto);
        return Result.ok(dto.pass() ? "已通过审核" : "已驳回", null);
    }

    @Operation(summary = "强制下架")
    @PutMapping("/items/{itemId}/off-shelf")
    public Result<Void> offShelf(@PathVariable Long itemId,
                                 @RequestBody(required = false) Map<String, String> body) {
        itemService.offShelfByAdmin(itemId, body == null ? null : body.get("reason"));
        return Result.ok("已下架", null);
    }

    @Operation(summary = "恢复上架")
    @PutMapping("/items/{itemId}/on-shelf")
    public Result<Void> onShelf(@PathVariable Long itemId) {
        itemService.onShelfByAdmin(itemId);
        return Result.ok("已恢复上架", null);
    }

    @Operation(summary = "删除商品")
    @DeleteMapping("/items/{itemId}")
    public Result<Void> delete(@PathVariable Long itemId) {
        itemService.delete(itemId);
        return Result.ok("已删除", null);
    }

    @Operation(summary = "批量通过审核")
    @PutMapping("/items/batch/audit")
    public Result<Map<String, Object>> batchAudit(@Valid @RequestBody Dtos.BatchIdsDTO dto) {
        int count = itemService.batchAudit(dto.ids());
        return Result.ok("已通过 " + count + " 件商品", Map.of("count", count));
    }

    @Operation(summary = "批量下架")
    @PutMapping("/items/batch/off-shelf")
    public Result<Map<String, Object>> batchOffShelf(@Valid @RequestBody Dtos.BatchIdsDTO dto) {
        int count = itemService.batchOffShelf(dto.ids());
        return Result.ok("已下架 " + count + " 件商品", Map.of("count", count));
    }
}
