package com.bobo.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bobo.common.PageResult;
import com.bobo.common.exception.BizException;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.entity.Item;
import com.bobo.domain.entity.Notice;
import com.bobo.domain.entity.Order;
import com.bobo.domain.entity.Review;
import com.bobo.domain.query.ReviewQuery;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.mapper.ItemMapper;
import com.bobo.mapper.OrderMapper;
import com.bobo.mapper.ReviewMapper;
import com.bobo.mapper.UserMapper;
import com.bobo.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 评价服务：订单完成后双向评价，评价同时推进订单到「已完成」。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewMapper reviewMapper;
    private final ItemMapper itemMapper;
    private final UserMapper userMapper;
    private final OrderMapper orderMapper;
    private final OrderService orderService;
    private final NotificationService notificationService;

    /** 提交评价 */
    @Transactional(rollbackFor = Exception.class)
    public Long submit(Dtos.ReviewDTO dto) {
        Long userId = AuthContext.userId();
        Order order = orderService.requireOrder(dto.orderId());
        if (!orderService.isParticipant(order, userId)) {
            throw BizException.forbidden("你无权评价该订单");
        }
        if (order.getStatus() != Order.STATUS_WAIT_REVIEW && order.getStatus() != Order.STATUS_FINISHED) {
            throw new BizException("订单完成后才能评价");
        }
        if (orderService.hasReviewed(order.getOrderId(), userId)) {
            throw new BizException("你已经评价过该订单了");
        }

        boolean isBuyer = userId.equals(order.getBuyerId());
        Long toId = isBuyer ? order.getSellerId() : order.getBuyerId();

        Review review = new Review();
        review.setOrderId(order.getOrderId());
        review.setItemId(order.getItemId());
        review.setFromId(userId);
        review.setToId(toId);
        review.setScore(dto.score());
        review.setContent(StringUtils.hasText(dto.content()) ? dto.content().trim() : null);
        review.setRole(isBuyer ? Review.ROLE_BUYER : Review.ROLE_SELLER);
        review.setStatus(Review.STATUS_VISIBLE);
        review.setDelFlag(0);
        reviewMapper.insert(review);

        // 被评价方信用分 +1（封顶 150，由 SQL 保证）
        int delta = dto.score() >= 4 ? 1 : (dto.score() <= 2 ? -1 : 0);
        if (delta != 0) {
            userMapper.changeCreditScore(toId, delta);
        }

        // 双方都评完 → 订单完成；单人评价也允许订单完成（对方 7 天内不评视为放弃由运营处理）
        boolean peerReviewed = orderService.hasReviewed(order.getOrderId(), toId);
        if (peerReviewed || isBuyer) {
            Order update = new Order();
            update.setOrderId(order.getOrderId());
            update.setStatus(Order.STATUS_FINISHED);
            update.setFinishTime(java.time.LocalDateTime.now());
            orderMapper.updateById(update);
        }

        notificationService.notify("你收到一条新评价",
                "「" + order.getItemTitle() + "」的" + (isBuyer ? "买家" : "卖家")
                        + "给了你 " + dto.score() + " 星评价",
                Notice.TYPE_ORDER, order.getOrderId(), toId);

        return review.getReviewId();
    }

    /** 评价分页（公开） */
    public PageResult<SimpleVos.ReviewVO> page(ReviewQuery query) {
        Page<SimpleVos.ReviewVO> page = new Page<>(query.safePageNum(), query.safePageSize());
        IPage<SimpleVos.ReviewVO> result = reviewMapper.selectReviewPage(page, query);
        return PageResult.of(result);
    }

    /** 我发出的评价 */
    public PageResult<SimpleVos.ReviewVO> myReviews(ReviewQuery query) {
        query.setFromId(AuthContext.userId());
        return page(query);
    }

    /** 某用户收到的评价（个人主页） */
    public List<SimpleVos.ReviewVO> received(Long userId, int limit) {
        return reviewMapper.selectReceivedReviews(userId, limit <= 0 || limit > 50 ? 10 : limit);
    }

    /** 某用户的评分摘要：{avgScore, total} */
    public Map<String, Object> scoreSummary(Long userId) {
        return reviewMapper.selectScoreSummary(userId);
    }

    /** 某商品的评分摘要 */
    public Map<String, Object> itemScoreSummary(Long itemId) {
        return reviewMapper.selectItemScoreSummary(itemId);
    }

    // ------------------------------------------------------------------ 管理端

    /** 隐藏 / 显示评价 */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long reviewId, int status) {
        Review review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw BizException.notFound("评价不存在");
        }
        reviewMapper.updateStatus(reviewId, status);
    }

    /** 删除评价（逻辑删除） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long reviewId) {
        Review review = reviewMapper.selectById(reviewId);
        if (review == null) {
            throw BizException.notFound("评价不存在");
        }
        reviewMapper.deleteById(reviewId);
        // 删除违规评价时把该条带来的信用分调整回退
        if (review.getScore() != null && review.getScore() >= 4) {
            userMapper.changeCreditScore(review.getToId(), -1);
        }
    }

    public long countAll() {
        Long count = reviewMapper.selectCount(Wrappers.<Review>lambdaQuery());
        return count == null ? 0L : count;
    }

    /** 全站平均评分（后台概览） */
    public double averageScore() {
        return reviewMapper.avgScore();
    }

    /** 商品信息（评价文案里要用标题） */
    public Item item(Long itemId) {
        return itemMapper.selectById(itemId);
    }
}
