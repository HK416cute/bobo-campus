package com.bobo.controller.admin;

import com.bobo.common.PageResult;
import com.bobo.common.Result;
import com.bobo.domain.query.OrderQuery;
import com.bobo.domain.vo.OrderVO;
import com.bobo.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端订单查询（管理员只读：交易纠纷按线下流程处理，不提供改单能力）。
 * <p>
 * 注意：这里不提供 /counts。订单角标属于"我的订单"语义，
 * 全站口径的统计统一放在 /api/admin/dashboard。
 *
 * @author bobo
 */
@Tag(name = "23 管理端-订单")
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;

    @Operation(summary = "订单分页（全部用户，role 传 all）")
    @GetMapping
    public Result<PageResult<OrderVO>> page(OrderQuery query) {
        return Result.ok(orderService.adminPage(query));
    }

    @Operation(summary = "订单详情")
    @GetMapping("/{orderId}")
    public Result<OrderVO> detail(@PathVariable Long orderId) {
        return Result.ok(orderService.detail(orderId));
    }
}
