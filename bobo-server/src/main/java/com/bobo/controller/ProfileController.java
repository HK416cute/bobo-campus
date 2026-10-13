package com.bobo.controller;

import com.bobo.common.PageResult;
import com.bobo.common.Result;
import com.bobo.domain.entity.Item;
import com.bobo.domain.query.ItemQuery;
import com.bobo.domain.vo.ItemCardVO;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.security.Anonymous;
import com.bobo.security.AuthContext;
import com.bobo.service.ItemService;
import com.bobo.service.ReviewService;
import com.bobo.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 个人主页接口：他人主页、主页商品、主页评价、我的发布。
 *
 * @author bobo
 */
@Tag(name = "09 个人主页")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProfileController {

    private final UserService userService;
    private final ItemService itemService;
    private final ReviewService reviewService;

    @Anonymous
    @Operation(summary = "用户公开主页（资料 + 评分 + 商品数）")
    @GetMapping("/users/{userId}")
    public Result<Map<String, Object>> profile(@PathVariable Long userId) {
        return Result.ok(userService.publicProfile(userId));
    }

    @Anonymous
    @Operation(summary = "某用户的商品分页（默认只看在售）")
    @GetMapping("/users/{userId}/items")
    public Result<PageResult<ItemCardVO>> items(@PathVariable Long userId, ItemQuery query) {
        query.setSellerId(userId);
        if (query.getStatus() == null) {
            query.setStatus(Item.STATUS_ON_SALE);
        }
        return Result.ok(itemService.page(query));
    }

    @Anonymous
    @Operation(summary = "某用户收到的评价")
    @GetMapping("/users/{userId}/reviews")
    public Result<List<SimpleVos.ReviewVO>> reviews(@PathVariable Long userId,
                                                    @RequestParam(defaultValue = "10") int limit) {
        return Result.ok(reviewService.received(userId, limit));
    }

    @Operation(summary = "我发布的商品分页")
    @GetMapping("/my/items")
    public Result<PageResult<ItemCardVO>> myItems(ItemQuery query) {
        // 自己的列表可以看到全部状态（待审核 / 已下架 / 已驳回）
        query.setSellerId(AuthContext.userId());
        return Result.ok(itemService.page(query));
    }
}
