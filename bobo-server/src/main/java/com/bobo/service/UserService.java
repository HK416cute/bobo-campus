package com.bobo.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bobo.common.PageResult;
import com.bobo.common.exception.BizException;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.entity.Favorite;
import com.bobo.domain.entity.Item;
import com.bobo.domain.entity.Order;
import com.bobo.domain.entity.User;
import com.bobo.domain.query.UserQuery;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.mapper.FavoriteMapper;
import com.bobo.mapper.ItemMapper;
import com.bobo.mapper.OrderMapper;
import com.bobo.mapper.ReviewMapper;
import com.bobo.mapper.UserMapper;
import com.bobo.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户服务：管理端用户管理 + 公开个人主页。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final ItemMapper itemMapper;
    private final OrderMapper orderMapper;
    private final FavoriteMapper favoriteMapper;
    private final ReviewMapper reviewMapper;
    private final NotificationService notificationService;

    /** 后台用户分页 */
    public PageResult<SimpleVos.UserVO> adminPage(UserQuery query) {
        Page<SimpleVos.UserVO> page = new Page<>(query.safePageNum(), query.safePageSize());
        IPage<SimpleVos.UserVO> result = userMapper.selectUserPage(page, query);
        return PageResult.of(result);
    }

    /** 用户详情（后台）+ 行为统计 */
    public Map<String, Object> detail(Long userId) {
        SimpleVos.UserVO vo = userMapper.selectUserVO(userId);
        if (vo == null) {
            throw BizException.notFound("用户不存在");
        }
        Map<String, Object> map = new HashMap<>(12);
        map.put("user", vo);
        map.put("itemCount", itemMapper.selectCount(
                Wrappers.<Item>lambdaQuery().eq(Item::getSellerId, userId)));
        map.put("onSaleCount", itemMapper.selectCount(
                Wrappers.<Item>lambdaQuery().eq(Item::getSellerId, userId).eq(Item::getStatus, Item.STATUS_ON_SALE)));
        map.put("soldCount", itemMapper.selectCount(
                Wrappers.<Item>lambdaQuery().eq(Item::getSellerId, userId).eq(Item::getStatus, Item.STATUS_SOLD)));
        map.put("buyOrderCount", orderMapper.selectCount(
                Wrappers.<Order>lambdaQuery().eq(Order::getBuyerId, userId)));
        map.put("sellOrderCount", orderMapper.selectCount(
                Wrappers.<Order>lambdaQuery().eq(Order::getSellerId, userId)));
        map.put("favoriteCount", favoriteMapper.selectCount(
                Wrappers.<Favorite>lambdaQuery().eq(Favorite::getUserId, userId)));
        map.put("reviewSummary", reviewMapper.selectScoreSummary(userId));
        return map;
    }

    /** 公开个人主页：资料 + 评分摘要 + 商品数 */
    public Map<String, Object> publicProfile(Long userId) {
        SimpleVos.UserVO vo = userMapper.selectUserVO(userId);
        if (vo == null) {
            throw BizException.notFound("用户不存在");
        }
        Map<String, Object> map = new HashMap<>(8);
        map.put("user", vo);
        map.put("onSaleCount", itemMapper.selectCount(
                Wrappers.<Item>lambdaQuery().eq(Item::getSellerId, userId).eq(Item::getStatus, Item.STATUS_ON_SALE)));
        map.put("soldCount", itemMapper.selectCount(
                Wrappers.<Item>lambdaQuery().eq(Item::getSellerId, userId).eq(Item::getStatus, Item.STATUS_SOLD)));
        map.put("scoreSummary", reviewMapper.selectScoreSummary(userId));
        map.put("reviewCount", reviewMapper.selectCount(
                Wrappers.<com.bobo.domain.entity.Review>lambdaQuery()
                        .eq(com.bobo.domain.entity.Review::getToId, userId)
                        .eq(com.bobo.domain.entity.Review::getStatus, 0)));
        return map;
    }

    /** 封禁 / 解封 */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long userId, Dtos.UserManageDTO dto) {
        User user = requireUser(userId);
        if (user.isAdminUser()) {
            throw new BizException("管理员账号不能被封禁");
        }
        if (dto.status() == null) {
            throw new BizException("缺少要设置的状态");
        }
        userMapper.updateStatus(userId, dto.status());
        notificationService.notify(dto.status() == User.STATUS_BANNED ? "账号已被封禁" : "账号已恢复正常",
                dto.status() == User.STATUS_BANNED
                        ? "你的账号因违规被封禁" + (dto.reason() == null ? "" : "，原因：" + dto.reason())
                        : "你的账号已解封，可以继续交易了",
                com.bobo.domain.entity.Notice.TYPE_SYSTEM, null, userId);
    }

    /** 学生认证审核 */
    @Transactional(rollbackFor = Exception.class)
    public void auditVerify(Long userId, Dtos.UserManageDTO dto) {
        requireUser(userId);
        if (dto.verifyStatus() == null
                || (dto.verifyStatus() != User.VERIFY_PASSED && dto.verifyStatus() != User.VERIFY_REJECTED)) {
            throw new BizException("认证审核结果只能是「通过」或「驳回」");
        }
        User update = new User();
        update.setUserId(userId);
        update.setVerifyStatus(dto.verifyStatus());
        userMapper.updateById(update);

        boolean passed = dto.verifyStatus() == User.VERIFY_PASSED;
        notificationService.notify(passed ? "学生认证已通过" : "学生认证未通过",
                passed ? "你已完成学生认证，个人主页会展示认证标识"
                        : "学生认证未通过" + (dto.reason() == null ? "" : "：" + dto.reason()),
                com.bobo.domain.entity.Notice.TYPE_SYSTEM, null, userId);
    }

    /** 调整信用分 */
    @Transactional(rollbackFor = Exception.class)
    public void changeCredit(Long userId, Dtos.UserManageDTO dto) {
        requireUser(userId);
        if (dto.creditDelta() == null || dto.creditDelta() == 0) {
            throw new BizException("请填写要调整的信用分（可正可负）");
        }
        userMapper.changeCreditScore(userId, dto.creditDelta());
        notificationService.notify("信用分变动",
                "你的信用分" + (dto.creditDelta() > 0 ? "增加" : "减少") + Math.abs(dto.creditDelta()) + " 分"
                        + (dto.reason() == null ? "" : "，原因：" + dto.reason()),
                com.bobo.domain.entity.Notice.TYPE_SYSTEM, null, userId);
    }

    /** 统计：总用户数、今日新增、管理员数 */
    public Map<String, Object> stats() {
        Map<String, Object> map = new HashMap<>(4);
        map.put("userTotal", userMapper.selectCount(Wrappers.<User>lambdaQuery()));
        map.put("userToday", userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .ge(User::getCreateTime, java.time.LocalDate.now().atStartOfDay())));
        map.put("adminTotal", userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getRole, User.ROLE_ADMIN)));
        map.put("bannedTotal", userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getStatus, User.STATUS_BANNED)));
        return map;
    }

    /** 取用户并校验存在 */
    public User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.notFound("用户不存在");
        }
        return user;
    }

    /** 当前登录用户实体（管理端操作日志用） */
    public User currentAdmin() {
        return requireUser(AuthContext.userId());
    }
}
