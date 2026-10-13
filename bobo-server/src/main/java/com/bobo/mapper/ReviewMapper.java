package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.bobo.domain.entity.Review;
import com.bobo.domain.query.ReviewQuery;
import com.bobo.domain.vo.SimpleVos;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 评价 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface ReviewMapper extends BaseMapper<Review> {

    IPage<SimpleVos.ReviewVO> selectReviewPage(IPage<SimpleVos.ReviewVO> page,
                                               @Param("q") ReviewQuery query);

    /** 用户主页：收到的评价（按时间倒序） */
    @Select("""
            SELECT r.review_id AS reviewId, r.order_id AS orderId, r.item_id AS itemId,
                   i.title AS itemTitle, r.from_id AS fromId, u.nickname AS fromNickname,
                   u.avatar AS fromAvatar, r.to_id AS toId, NULL AS toNickname,
                   r.score AS score, r.content AS content, r.role AS role, r.status AS status,
                   r.create_time AS createTime
              FROM bobo_review r
              LEFT JOIN bobo_user u ON u.user_id = r.from_id
              LEFT JOIN bobo_item i ON i.item_id = r.item_id
             WHERE r.del_flag = 0 AND r.status = 0 AND r.to_id = #{userId}
             ORDER BY r.create_time DESC
             LIMIT #{limit}
            """)
    List<SimpleVos.ReviewVO> selectReceivedReviews(@Param("userId") Long userId, @Param("limit") int limit);

    /** 用户的平均评分与评价数，返回 {avgScore, total} */
    @Select("""
            SELECT IFNULL(ROUND(AVG(score), 1), 0) AS avgScore, COUNT(*) AS total
              FROM bobo_review
             WHERE del_flag = 0 AND status = 0 AND to_id = #{userId}
            """)
    Map<String, Object> selectScoreSummary(@Param("userId") Long userId);

    /** 商品的好评率与评价数 */
    @Select("""
            SELECT IFNULL(ROUND(AVG(score), 1), 5) AS avgScore, COUNT(*) AS total
              FROM bobo_review
             WHERE del_flag = 0 AND status = 0 AND item_id = #{itemId} AND role = 'buyer'
            """)
    Map<String, Object> selectItemScoreSummary(@Param("itemId") Long itemId);

    /** 隐藏 / 显示评价 */
    @Update("UPDATE bobo_review SET status = #{status} WHERE review_id = #{reviewId}")
    int updateStatus(@Param("reviewId") Long reviewId, @Param("status") Integer status);

    /** 平均分（后台概览） */
    @Select("SELECT IFNULL(ROUND(AVG(score), 2), 0) FROM bobo_review WHERE del_flag = 0 AND status = 0")
    double avgScore();
}
