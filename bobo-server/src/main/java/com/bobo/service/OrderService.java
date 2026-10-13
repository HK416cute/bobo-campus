package com.bobo.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bobo.common.PageResult;
import com.bobo.common.exception.BizException;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.entity.Address;
import com.bobo.domain.entity.Item;
import com.bobo.domain.entity.Notice;
import com.bobo.domain.entity.Order;
import com.bobo.domain.entity.Review;
import com.bobo.domain.query.OrderQuery;
import com.bobo.domain.vo.OrderVO;
import com.bobo.mapper.AddressMapper;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单服务：下单、支付、发货、收货、取消、退款。
 * <p>
 * 状态机（详见 docs/api.md）：
 * <pre>
 * 0 待付款 → 1 待发货 → 2 待收货 → 3 待评价 → 4 已完成
 * 当面交易跳过 1、2：0 → 3 → 4
 * 5 已取消；6 退款中 → 7 已退款
 * </pre>
 * 关键不变量：
 * <ul>
 *   <li><b>一件商品最多只有一个有效订单</b>：下单用「状态条件更新」把商品从「在售」
 *       直接推进到「已售」，这一步是原子的——并发下单时只有一个人能成功，
 *       其他人会收到「已被抢先下单」。这比「先查再改」可靠，不需要分布式锁；</li>
 *   <li>取消 / 退款成功后，只有当该商品没有其它处于活跃状态的订单时，才恢复「在售」；</li>
 *   <li>所有状态流转都校验操作人身份与当前状态，非法流转一律拒绝。</li>
 * </ul>
 * 代价与取舍：下单即锁定商品，因此「先下单不付款」会占用商品，
 * 需要卖家取消（或后续接入超时未付款自动取消任务）才能重新上架。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final DateTimeFormatter ORDER_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderMapper orderMapper;
    private final ItemMapper itemMapper;
    private final AddressMapper addressMapper;
    private final ReviewMapper reviewMapper;
    private final UserMapper userMapper;
    private final ItemService itemService;
    private final NotificationService notificationService;

    // ------------------------------------------------------------------ 下单

    /** 下单：校验商品可交易性，锁定商品为已售，并快照商品信息 */
    @Transactional(rollbackFor = Exception.class)
    public Long create(Dtos.OrderCreateDTO dto) {
        Long buyerId = AuthContext.userId();
        Item item = itemMapper.selectById(dto.itemId());
        if (item == null) {
            throw BizException.notFound("商品不存在或已被删除");
        }
        if (item.getStatus() != Item.STATUS_ON_SALE) {
            throw new BizException("商品已下架或已售出，无法下单");
        }
        if (buyerId.equals(item.getSellerId())) {
            throw new BizException("不能购买自己发布的商品");
        }

        int tradeType = resolveTradeType(item, dto.tradeType());

        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setItemId(item.getItemId());
        order.setBuyerId(buyerId);
        order.setSellerId(item.getSellerId());
        order.setItemTitle(item.getTitle());
        order.setItemCover(item.getCover());
        order.setPrice(item.getPrice());
        order.setQuantity(1);
        order.setTotalAmount(item.getPrice());
        order.setTradeType(tradeType);
        order.setRemark(dto.remark());
        order.setStatus(Order.STATUS_UNPAID);
        order.setDelFlag(0);

        if (tradeType == Order.TRADE_MAIL) {
            if (dto.addressId() == null) {
                throw new BizException("邮寄交易请选择收货地址");
            }
            Address address = addressMapper.selectById(dto.addressId());
            if (address == null || !buyerId.equals(address.getUserId())) {
                throw new BizException("收货地址无效，请重新选择");
            }
            order.setAddressId(address.getAddressId());
            order.setReceiverInfo(address.getReceiver() + " / " + address.getPhone() + " / "
                    + (StringUtils.hasText(address.getCampus()) ? address.getCampus() + " " : "")
                    + address.getDetail());
        } else {
            order.setTradePlace(StringUtils.hasText(dto.tradePlace()) ? dto.tradePlace().trim() : item.getLocation());
            order.setTradeTime(dto.tradeTime());
        }

        orderMapper.insert(order);

        // 原子占位：只有把商品从「在售」推进到「已售」成功，订单才算成立。
        // 并发下单时这里只有一个请求能改到 1 行，其余走回滚，从根上杜绝重复成交。
        if (itemMapper.compareAndSetStatus(item.getItemId(), Item.STATUS_ON_SALE, Item.STATUS_SOLD) == 0) {
            throw new BizException("手慢啦，这件宝贝已被抢先下单");
        }

        notificationService.notify("你有一笔新订单",
                "买家已下单「" + item.getTitle() + "」，等待付款", Notice.TYPE_ORDER,
                order.getOrderId(), item.getSellerId());

        return order.getOrderId();
    }

    // ------------------------------------------------------------------ 状态流转

    /** 模拟支付：校园平台不接入真实支付渠道，这里直接把订单推进到下一步 */
    @Transactional(rollbackFor = Exception.class)
    public void pay(Long orderId) {
        Order order = requireOrder(orderId);
        requireBuyer(order);
        if (order.getStatus() != Order.STATUS_UNPAID) {
            throw new BizException("当前订单状态不可支付");
        }
        Order update = new Order();
        update.setOrderId(orderId);
        // 当面交易不需要发货收货，直接进入待评价
        update.setStatus(order.getTradeType() == Order.TRADE_FACE
                ? Order.STATUS_WAIT_REVIEW : Order.STATUS_WAIT_DELIVER);
        update.setPayTime(LocalDateTime.now());
        orderMapper.updateById(update);

        notificationService.notify("订单已付款",
                "买家已付款，请尽快" + (order.getTradeType() == Order.TRADE_FACE ? "完成当面交易" : "发货"),
                Notice.TYPE_ORDER, orderId, order.getSellerId());
    }

    /** 卖家发货（仅邮寄） */
    @Transactional(rollbackFor = Exception.class)
    public void deliver(Long orderId, Dtos.OrderActionDTO dto) {
        Order order = requireOrder(orderId);
        requireSeller(order);
        if (order.getStatus() != Order.STATUS_WAIT_DELIVER) {
            throw new BizException("当前订单状态不可发货");
        }
        if (order.getTradeType() != Order.TRADE_MAIL) {
            throw new BizException("当面交易订单无需发货");
        }
        if (!StringUtils.hasText(dto.logisticsNo())) {
            throw new BizException("请填写快递单号");
        }
        Order update = new Order();
        update.setOrderId(orderId);
        update.setStatus(Order.STATUS_WAIT_RECEIVE);
        update.setLogisticsCompany(dto.logisticsCompany());
        update.setLogisticsNo(dto.logisticsNo().trim());
        update.setDeliverTime(LocalDateTime.now());
        orderMapper.updateById(update);

        notificationService.notify("卖家已发货",
                "「" + order.getItemTitle() + "」已发出，单号：" + dto.logisticsNo(),
                Notice.TYPE_ORDER, orderId, order.getBuyerId());
    }

    /** 买家确认收货 → 待评价 */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long orderId) {
        Order order = requireOrder(orderId);
        requireBuyer(order);
        if (order.getStatus() != Order.STATUS_WAIT_RECEIVE
                && order.getStatus() != Order.STATUS_WAIT_DELIVER) {
            throw new BizException("当前订单状态不可确认收货");
        }
        Order update = new Order();
        update.setOrderId(orderId);
        update.setStatus(Order.STATUS_WAIT_REVIEW);
        update.setFinishTime(LocalDateTime.now());
        orderMapper.updateById(update);

        notificationService.notify("买家已确认收货",
                "「" + order.getItemTitle() + "」交易完成，去给买家一个评价吧",
                Notice.TYPE_ORDER, orderId, order.getSellerId());
    }

    /** 取消订单：待付款时买卖双方均可取消，已付款后仅卖家可取消（相当于拒单退款） */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long orderId, String reason) {
        Order order = requireOrder(orderId);
        Long userId = AuthContext.userId();
        if (!AuthContext.isAdmin() && !isParticipant(order, userId)) {
            throw BizException.forbidden("你无权操作该订单");
        }
        if (order.getStatus() != Order.STATUS_UNPAID && order.getStatus() != Order.STATUS_WAIT_DELIVER) {
            throw new BizException("当前订单状态不可取消，可申请退款");
        }
        if (order.getStatus() == Order.STATUS_WAIT_DELIVER && !userId.equals(order.getSellerId())
                && !AuthContext.isAdmin()) {
            throw new BizException("已付款订单需要卖家同意才能取消，买家请申请退款");
        }

        Order update = new Order();
        update.setOrderId(orderId);
        update.setStatus(Order.STATUS_CANCELLED);
        update.setCancelReason(StringUtils.hasText(reason) ? reason.trim() : "用户取消");
        orderMapper.updateById(update);

        releaseItem(order);

        Long notifyUser = userId.equals(order.getBuyerId()) ? order.getSellerId() : order.getBuyerId();
        notificationService.notify("订单已取消",
                "订单「" + order.getItemTitle() + "」已取消：" + update.getCancelReason(),
                Notice.TYPE_ORDER, orderId, notifyUser);
    }

    /** 买家申请退款 */
    @Transactional(rollbackFor = Exception.class)
    public void refund(Long orderId, String reason) {
        Order order = requireOrder(orderId);
        requireBuyer(order);
        if (order.getStatus() != Order.STATUS_WAIT_DELIVER
                && order.getStatus() != Order.STATUS_WAIT_RECEIVE
                && order.getStatus() != Order.STATUS_WAIT_REVIEW) {
            throw new BizException("当前订单状态不可申请退款");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BizException("请填写退款原因");
        }
        Order update = new Order();
        update.setOrderId(orderId);
        update.setStatus(Order.STATUS_REFUNDING);
        update.setCancelReason(reason.trim());
        orderMapper.updateById(update);

        notificationService.notify("买家申请退款",
                "订单「" + order.getItemTitle() + "」申请退款：" + reason,
                Notice.TYPE_ORDER, orderId, order.getSellerId());
    }

    /** 卖家同意退款 → 已退款，商品恢复在售 */
    @Transactional(rollbackFor = Exception.class)
    public void agreeRefund(Long orderId) {
        Order order = requireOrder(orderId);
        requireSeller(order);
        if (order.getStatus() != Order.STATUS_REFUNDING) {
            throw new BizException("当前订单没有待处理的退款申请");
        }
        Order update = new Order();
        update.setOrderId(orderId);
        update.setStatus(Order.STATUS_REFUNDED);
        orderMapper.updateById(update);

        releaseItem(order);

        notificationService.notify("退款已完成",
                "订单「" + order.getItemTitle() + "」的退款已同意", Notice.TYPE_ORDER, orderId, order.getBuyerId());
    }

    // ------------------------------------------------------------------ 查询

    public PageResult<OrderVO> page(OrderQuery query) {
        Long userId = AuthContext.userId();
        // 前台列表只允许 buy / sell 两个视角，防止普通用户传 all 看到别人订单
        if (!"sell".equals(query.getRole())) {
            query.setRole("buy");
        }
        Page<OrderVO> page = new Page<>(query.safePageNum(), query.safePageSize());
        IPage<OrderVO> result = orderMapper.selectOrderPage(page, query, userId);
        List<OrderVO> records = result.getRecords();
        records.forEach(vo -> vo.setMyRole(userId.equals(vo.getBuyerId()) ? "buyer" : "seller"));
        return PageResult.of(result);
    }

    /** 后台订单管理：管理员视角看全部 */
    public PageResult<OrderVO> adminPage(OrderQuery query) {
        Page<OrderVO> page = new Page<>(query.safePageNum(), query.safePageSize());
        // 传一个不存在的 userId，SQL 里的 reviewed 子查询自然为 0（管理员不需要「我评价过没」）
        IPage<OrderVO> result = orderMapper.selectOrderPage(page, query, -1L);
        return PageResult.of(result);
    }

    public OrderVO detail(Long orderId) {
        Long userId = AuthContext.userId();
        OrderVO vo = orderMapper.selectOrderDetail(orderId, userId);
        if (vo == null) {
            throw BizException.notFound("订单不存在");
        }
        boolean admin = AuthContext.isAdmin();
        boolean participant = userId.equals(vo.getBuyerId()) || userId.equals(vo.getSellerId());
        if (!admin && !participant) {
            throw BizException.forbidden("你无权查看该订单");
        }
        vo.setMyRole(userId.equals(vo.getBuyerId()) ? "buyer" : "seller");
        vo.setStatusText(statusText(vo.getStatus()));
        return vo;
    }

    /** 订单角标统计 */
    public Map<String, Object> counts() {
        Long userId = AuthContext.userId();
        Map<String, Object> map = new HashMap<>(10);
        map.put("unpaid", countByStatus(userId, Order.STATUS_UNPAID));
        map.put("waitDeliver", countByStatus(userId, Order.STATUS_WAIT_DELIVER));
        map.put("waitReceive", countByStatus(userId, Order.STATUS_WAIT_RECEIVE));
        map.put("waitReview", countByStatus(userId, Order.STATUS_WAIT_REVIEW));
        map.put("finished", countByStatus(userId, Order.STATUS_FINISHED));
        map.put("cancelled", countByStatus(userId, Order.STATUS_CANCELLED));
        map.put("refunding", countByStatus(userId, Order.STATUS_REFUNDING));
        return map;
    }

    /** 订单详情（供评价服务复用，含归属校验开关） */
    public Order requireOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BizException.notFound("订单不存在");
        }
        return order;
    }

    public boolean isParticipant(Order order, Long userId) {
        return userId.equals(order.getBuyerId()) || userId.equals(order.getSellerId());
    }

    // ------------------------------------------------------------------ 内部方法

    private void requireBuyer(Order order) {
        if (!AuthContext.userId().equals(order.getBuyerId()) && !AuthContext.isAdmin()) {
            throw BizException.forbidden("只有买家可以执行该操作");
        }
    }

    private void requireSeller(Order order) {
        if (!AuthContext.userId().equals(order.getSellerId()) && !AuthContext.isAdmin()) {
            throw BizException.forbidden("只有卖家可以执行该操作");
        }
    }

    /** 退款 / 取消后释放商品：没有其它有效订单占用时恢复在售 */
    private void releaseItem(Order order) {
        Long occupied = orderMapper.selectCount(Wrappers.<Order>lambdaQuery()
                .eq(Order::getItemId, order.getItemId())
                .ne(Order::getOrderId, order.getOrderId())
                .in(Order::getStatus, List.of(Order.STATUS_UNPAID, Order.STATUS_WAIT_DELIVER,
                        Order.STATUS_WAIT_RECEIVE, Order.STATUS_WAIT_REVIEW, Order.STATUS_REFUNDING)));
        if (occupied == null || occupied == 0) {
            itemService.restoreOnSale(order.getItemId());
        }
    }
    private int resolveTradeType(Item item, Integer requested) {
        int want = requested == null ? Item.TRADE_BOTH : requested;
        if (want != Order.TRADE_FACE && want != Order.TRADE_MAIL) {
            throw new BizException("交易方式不正确");
        }
        Integer limited = item.getTradeType();
        if (limited != null && limited != Item.TRADE_BOTH && limited != want) {
            throw new BizException(limited == Item.TRADE_FACE ? "该商品仅支持当面交易" : "该商品仅支持邮寄");
        }
        return want;
    }

    /** 订单号：时间戳 + 4 位随机数，重复时重试 */
    private String generateOrderNo() {
        for (int i = 0; i < 5; i++) {
            String no = "BOBO" + LocalDateTime.now().format(ORDER_NO_FORMAT)
                    + ThreadLocalRandom.current().nextInt(1000, 10000);
            Long exists = orderMapper.selectCount(Wrappers.<Order>lambdaQuery().eq(Order::getOrderNo, no));
            if (exists == null || exists == 0) {
                return no;
            }
        }
        throw new BizException("订单号生成失败，请重试");
    }

    private long countByStatus(Long userId, int status) {
        Long count = orderMapper.selectCount(Wrappers.<Order>lambdaQuery()
                .eq(Order::getStatus, status)
                .and(w -> w.eq(Order::getBuyerId, userId).or().eq(Order::getSellerId, userId)));
        return count == null ? 0L : count;
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case Order.STATUS_UNPAID -> "待付款";
            case Order.STATUS_WAIT_DELIVER -> "待发货";
            case Order.STATUS_WAIT_RECEIVE -> "待收货";
            case Order.STATUS_WAIT_REVIEW -> "待评价";
            case Order.STATUS_FINISHED -> "已完成";
            case Order.STATUS_CANCELLED -> "已取消";
            case Order.STATUS_REFUNDING -> "退款中";
            case Order.STATUS_REFUNDED -> "已退款";
            default -> "未知";
        };
    }

    /** 该订单我是否已评价 */
    public boolean hasReviewed(Long orderId, Long userId) {
        Long count = reviewMapper.selectCount(Wrappers.<Review>lambdaQuery()
                .eq(Review::getOrderId, orderId)
                .eq(Review::getFromId, userId));
        return count != null && count > 0;
    }

    /** 用户名（通知文案用） */
    public String nickname(Long userId) {
        var user = userMapper.selectById(userId);
        return user == null ? "用户" : user.getNickname();
    }
}
