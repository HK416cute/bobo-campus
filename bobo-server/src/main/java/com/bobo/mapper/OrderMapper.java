package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.Order;
import com.bobo.domain.query.OrderQuery;
import com.bobo.domain.vo.OrderVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 订单 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /** 订单分页（前台「我买到的 / 我卖出的」与后台订单管理共用） */
    IPage<OrderVO> selectOrderPage(IPage<OrderVO> page,
                                   @Param("q") OrderQuery query,
                                   @Param("currentUserId") Long currentUserId);

    /** 订单详情 */
    OrderVO selectOrderDetail(@Param("orderId") Long orderId,
                              @Param("currentUserId") Long currentUserId);

    /** 订单状态分布 */
    @Select("SELECT status AS `status`, COUNT(*) AS `total` FROM bobo_order WHERE del_flag = 0 GROUP BY status")
    List<Map<String, Object>> countByStatus();

    /** 近 N 天每日成交额与订单量（后台趋势图） */
    @Select("""
            SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS `day`,
                   COUNT(*) AS `orderCount`,
                   IFNULL(SUM(CASE WHEN status IN (3, 4) THEN total_amount ELSE 0 END), 0) AS `amount`
              FROM bobo_order
             WHERE del_flag = 0 AND create_time >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
             GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d')
             ORDER BY `day`
            """)
    List<Map<String, Object>> selectDailyTrend(@Param("days") int days);

    /** 已进入待评价状态且超过 N 天未处理的订单（运营巡检用） */
    @Select("""
            SELECT COUNT(*) FROM bobo_order
             WHERE del_flag = 0 AND status = 3 AND update_time < DATE_SUB(NOW(), INTERVAL #{hours} HOUR)
            """)
    long countStaleWaitReview(@Param("hours") int hours);

    /** 创建时间区间内的订单数（数据概览） */
    @Select("SELECT COUNT(*) FROM bobo_order WHERE del_flag = 0 AND create_time >= #{from}")
    long countSince(@Param("from") Date from);
}
