package com.bobo.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bobo.domain.entity.Item;
import com.bobo.domain.entity.Order;
import com.bobo.domain.entity.Review;
import com.bobo.domain.entity.User;
import com.bobo.mapper.FavoriteMapper;
import com.bobo.mapper.ItemMapper;
import com.bobo.mapper.OrderMapper;
import com.bobo.mapper.ReportMapper;
import com.bobo.mapper.ReviewMapper;
import com.bobo.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台数据统计服务。
 * <p>
 * 统计 SQL 的列别名统一用驼峰（如 {@code orderCount}），
 * 直接映射到 Map 的 key，前端不需要再做字段名转换。
 *
 * @author bobo
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final UserMapper userMapper;
    private final ItemMapper itemMapper;
    private final OrderMapper orderMapper;
    private final ReviewMapper reviewMapper;
    private final FavoriteMapper favoriteMapper;
    private final ReportMapper reportMapper;

    /** 数据概览卡片 */
    public Map<String, Object> dashboard() {
        Map<String, Object> map = new HashMap<>(20);

        long userTotal = count(userMapper.selectCount(Wrappers.<User>lambdaQuery()));
        long userToday = count(userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .ge(User::getCreateTime, java.time.LocalDate.now().atStartOfDay())));

        map.put("userTotal", userTotal);
        map.put("userToday", userToday);
        map.put("itemTotal", count(itemMapper.selectCount(Wrappers.<Item>lambdaQuery())));
        map.put("itemPending", count(itemMapper.selectCount(
                Wrappers.<Item>lambdaQuery().eq(Item::getStatus, Item.STATUS_PENDING))));
        map.put("itemOnSale", count(itemMapper.selectCount(
                Wrappers.<Item>lambdaQuery().eq(Item::getStatus, Item.STATUS_ON_SALE))));
        map.put("itemSold", count(itemMapper.selectCount(
                Wrappers.<Item>lambdaQuery().eq(Item::getStatus, Item.STATUS_SOLD))));

        map.put("orderTotal", count(orderMapper.selectCount(Wrappers.<Order>lambdaQuery())));
        map.put("orderToday", count(orderMapper.selectCount(Wrappers.<Order>lambdaQuery()
                .ge(Order::getCreateTime, java.time.LocalDate.now().atStartOfDay()))));
        map.put("gmv", gmv());

        map.put("reviewTotal", count(reviewMapper.selectCount(Wrappers.<Review>lambdaQuery())));
        map.put("avgScore", reviewMapper.avgScore());
        map.put("favoriteTotal", favoriteMapper.countAll());

        map.put("reportPending", reportMapper.countPending());
        return map;
    }

    /** 后台红点：待处理事项 */
    public Map<String, Object> pending() {
        Map<String, Object> map = new HashMap<>(4);
        map.put("reportPending", reportMapper.countPending());
        map.put("itemPending", count(itemMapper.selectCount(
                Wrappers.<Item>lambdaQuery().eq(Item::getStatus, Item.STATUS_PENDING))));
        map.put("refunding", count(orderMapper.selectCount(
                Wrappers.<Order>lambdaQuery().eq(Order::getStatus, Order.STATUS_REFUNDING))));
        return map;
    }

    /** 近 N 天趋势（订单量 + 成交额），补齐没有数据的日期，前端画图不用再处理断点 */
    public List<Map<String, Object>> trend(int days) {
        int span = days <= 0 ? 7 : Math.min(days, 30);
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        java.time.LocalDate today = java.time.LocalDate.now();
        for (int i = span - 1; i >= 0; i--) {
            String day = today.minusDays(i).toString();
            Map<String, Object> row = new HashMap<>(3);
            row.put("day", day);
            row.put("orderCount", 0L);
            row.put("amount", BigDecimal.ZERO);
            indexed.put(day, row);
        }
        for (Map<String, Object> row : orderMapper.selectDailyTrend(span)) {
            Object day = row.get("day");
            if (day != null && indexed.containsKey(day.toString())) {
                Map<String, Object> target = indexed.get(day.toString());
                target.put("orderCount", row.get("orderCount"));
                target.put("amount", row.get("amount"));
            }
        }
        return new ArrayList<>(indexed.values());
    }

    /** 商品状态分布（饼图） */
    public List<Map<String, Object>> itemStatusDistribution() {
        List<Map<String, Object>> rows = itemMapper.countByStatus();
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new HashMap<>(3);
            int status = row.get("status") == null ? -1 : ((Number) row.get("status")).intValue();
            item.put("status", status);
            item.put("name", statusText(status));
            item.put("total", row.get("total"));
            result.add(item);
        }
        return result;
    }

    /** 分类商品分布（柱图） */
    public List<Map<String, Object>> categoryDistribution() {
        List<Map<String, Object>> rows = itemMapper.countByCategory();
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new HashMap<>(3);
            item.put("categoryName", row.get("categoryName"));
            item.put("total", row.get("total"));
            result.add(item);
        }
        return result;
    }

    /** 平台累计成交额（已完成 / 待评价的订单） */
    private String gmv() {
        List<Order> orders = orderMapper.selectList(Wrappers.<Order>lambdaQuery()
                .in(Order::getStatus, List.of(Order.STATUS_WAIT_REVIEW, Order.STATUS_FINISHED))
                .select(Order::getTotalAmount));
        BigDecimal sum = orders.stream()
                .map(Order::getTotalAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.toPlainString();
    }

    private long reportPending() {
        return reportMapper.countPending();
    }

    private long count(Long value) {
        return value == null ? 0L : value;
    }

    private String statusText(int status) {
        return switch (status) {
            case Item.STATUS_PENDING -> "待审核";
            case Item.STATUS_ON_SALE -> "在售";
            case Item.STATUS_SOLD -> "已售";
            case Item.STATUS_OFF_SHELF -> "已下架";
            case Item.STATUS_REJECTED -> "已驳回";
            default -> "未知";
        };
    }
}
