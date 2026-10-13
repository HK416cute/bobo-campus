package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.bobo.domain.entity.Item;
import com.bobo.domain.query.ItemQuery;
import com.bobo.domain.vo.ItemCardVO;
import com.bobo.domain.vo.ItemDetailVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 商品 Mapper。列表 / 详情查询在 XML 中完成关联查询。
 *
 * @author bobo
 */
@Mapper
public interface ItemMapper extends BaseMapper<Item> {

    /** 商品列表（前台搜索、卖家主页、后台管理共用） */
    IPage<ItemCardVO> selectItemPage(IPage<ItemCardVO> page,
                                     @Param("q") ItemQuery query,
                                     @Param("currentUserId") Long currentUserId);

    /** 商品详情 */
    ItemDetailVO selectItemDetail(@Param("itemId") Long itemId,
                                  @Param("currentUserId") Long currentUserId);

    /** 卖家在售的其他商品 */
    List<ItemCardVO> selectSellerOtherItems(@Param("sellerId") Long sellerId,
                                            @Param("excludeItemId") Long excludeItemId,
                                            @Param("limit") int limit);

    /** 批量取商品卡片（订单、举报等场景回填展示信息） */
    List<ItemCardVO> selectCardsByIds(@Param("ids") List<Long> ids);

    /** 浏览量 +1 */
    @Update("UPDATE bobo_item SET view_count = view_count + 1 WHERE item_id = #{itemId}")
    int increaseViewCount(@Param("itemId") Long itemId);

    /** 想要数 +delta（可为负，且不会降到 0 以下） */
    @Update("UPDATE bobo_item SET want_count = GREATEST(want_count + #{delta}, 0) WHERE item_id = #{itemId}")
    int changeWantCount(@Param("itemId") Long itemId, @Param("delta") int delta);

    /** 上架 / 下架 / 售出等状态流转 */
    @Update("""
            UPDATE bobo_item
               SET status = #{status},
                   sold_time = CASE WHEN #{status} = 2 THEN NOW() ELSE sold_time END
             WHERE item_id = #{itemId}
            """)
    int updateStatus(@Param("itemId") Long itemId, @Param("status") Integer status);

    /**
     * 带前置状态条件的原子状态流转（CAS）。
     * 用于「下单即锁定商品」：只有当前状态等于 {@code expectStatus} 时才会更新，
     * 返回受影响行数为 0 说明被别人抢先改了状态，调用方据此回滚。
     */
    @Update("""
            UPDATE bobo_item
               SET status = #{newStatus},
                   sold_time = CASE WHEN #{newStatus} = 2 THEN NOW() ELSE sold_time END
             WHERE item_id = #{itemId} AND status = #{expectStatus} AND del_flag = 0
            """)
    int compareAndSetStatus(@Param("itemId") Long itemId,
                            @Param("expectStatus") Integer expectStatus,
                            @Param("newStatus") Integer newStatus);

    /** 商品状态分布统计，返回 [{status, total}] */
    @Select("SELECT status AS `status`, COUNT(*) AS `total` FROM bobo_item WHERE del_flag = 0 GROUP BY status")
    List<Map<String, Object>> countByStatus();

    /** 分类维度在售数量（后台分类管理页） */
    @Select("""
            SELECT c.category_id AS categoryId, c.name AS categoryName, COUNT(i.item_id) AS total
              FROM bobo_category c
              LEFT JOIN bobo_item i ON i.category_id = c.category_id AND i.del_flag = 0
             WHERE c.del_flag = 0
             GROUP BY c.category_id, c.name
             ORDER BY c.sort
            """)
    List<Map<String, Object>> countByCategory();

    // ------------------------------------------------------------------ AI 场景

    /**
     * 某分类在售商品的价格样本，供 AI 价格建议计算真实分位。
     * 只取在售（正在成交的价位才是有效锚点），已售/下架的价格不代表当前行情。
     */
    @Select("""
            SELECT price FROM bobo_item
             WHERE del_flag = 0 AND status = 1 AND category_id = #{categoryId} AND price IS NOT NULL
             ORDER BY create_time DESC
             LIMIT #{limit}
            """)
    List<java.math.BigDecimal> selectOnSalePrices(@Param("categoryId") Long categoryId,
                                                  @Param("limit") int limit);

    /** 全站在售商品的价格样本，供自然语言检索理解"这个平台大概什么价位" */
    @Select("""
            SELECT price FROM bobo_item
             WHERE del_flag = 0 AND status = 1 AND price IS NOT NULL
             ORDER BY create_time DESC
             LIMIT #{limit}
            """)
    List<java.math.BigDecimal> selectRecentOnSalePrices(@Param("limit") int limit);

    /** 有在售商品的校区清单，作为检索解析的候选集（避免模型编出不存在的校区） */
    @Select("""
            SELECT DISTINCT campus FROM bobo_item
             WHERE del_flag = 0 AND status = 1 AND campus IS NOT NULL AND campus <> ''
             ORDER BY campus
            """)
    List<String> selectActiveCampuses();
}
