package com.bobo.controller;

import com.bobo.common.PageResult;
import com.bobo.common.Result;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.query.OrderQuery;
import com.bobo.domain.vo.OrderVO;
import com.bobo.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 订单接口（前台）。
 * <p>
 * 订单动作统一走 POST + 子路径，而不是 PATCH，理由：
 * 这些动作都是「业务事件」（支付、发货、收货），不是资源字段的部分更新，
 * 用动词式子路径语义更清楚，前端也更直观。
 *
 * @author bobo
 */
@Tag(name = "07 订单")
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "下单")
    @PostMapping
    public Result<Map<String, Object>> create(@Valid @RequestBody Dtos.OrderCreateDTO dto) {
        Long orderId = orderService.create(dto);
        return Result.ok("下单成功，请尽快完成交易", Map.of("orderId", orderId));
    }

    @Operation(summary = "我的订单分页（buy 我买到的 / sell 我卖出的）")
    @GetMapping
    public Result<PageResult<OrderVO>> page(OrderQuery query) {
        return Result.ok(orderService.page(query));
    }

    @Operation(summary = "订单角标统计")
    @GetMapping("/counts")
    public Result<Map<String, Object>> counts() {
        return Result.ok(orderService.counts());
    }

    @Operation(summary = "订单详情")
    @GetMapping("/{orderId}")
    public Result<OrderVO> detail(@PathVariable Long orderId) {
        return Result.ok(orderService.detail(orderId));
    }

    @Operation(summary = "支付（校园平台为模拟支付）")
    @PostMapping("/{orderId}/pay")
    public Result<Void> pay(@PathVariable Long orderId) {
        orderService.pay(orderId);
        return Result.ok("支付成功", null);
    }

    @Operation(summary = "卖家发货")
    @PostMapping("/{orderId}/deliver")
    public Result<Void> deliver(@PathVariable Long orderId, @RequestBody Dtos.OrderActionDTO dto) {
        orderService.deliver(orderId, dto);
        return Result.ok("已发货", null);
    }

    @Operation(summary = "买家确认收货")
    @PostMapping("/{orderId}/confirm")
    public Result<Void> confirm(@PathVariable Long orderId) {
        orderService.confirm(orderId);
        return Result.ok("已确认收货，别忘了评价对方", null);
    }

    @Operation(summary = "取消订单")
    @PostMapping("/{orderId}/cancel")
    public Result<Void> cancel(@PathVariable Long orderId, @RequestBody(required = false) Dtos.OrderActionDTO dto) {
        orderService.cancel(orderId, dto == null ? null : dto.reason());
        return Result.ok("订单已取消", null);
    }

    @Operation(summary = "买家申请退款")
    @PostMapping("/{orderId}/refund")
    public Result<Void> refund(@PathVariable Long orderId, @RequestBody(required = false) Dtos.OrderActionDTO dto) {
        orderService.refund(orderId, dto == null ? null : dto.reason());
        return Result.ok("退款申请已提交，等待卖家处理", null);
    }

    @Operation(summary = "卖家同意退款")
    @PostMapping("/{orderId}/refund/agree")
    public Result<Void> agreeRefund(@PathVariable Long orderId) {
        orderService.agreeRefund(orderId);
        return Result.ok("已同意退款", null);
    }
}
