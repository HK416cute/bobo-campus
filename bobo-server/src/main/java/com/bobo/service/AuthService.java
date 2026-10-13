package com.bobo.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bobo.common.exception.BizException;
import com.bobo.config.props.BoboProperties;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.entity.Favorite;
import com.bobo.domain.entity.Item;
import com.bobo.domain.entity.Notice;
import com.bobo.domain.entity.Order;
import com.bobo.domain.entity.User;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.mapper.ConversationMapper;
import com.bobo.mapper.FavoriteMapper;
import com.bobo.mapper.ItemMapper;
import com.bobo.mapper.NoticeMapper;
import com.bobo.mapper.OrderMapper;
import com.bobo.mapper.UserMapper;
import com.bobo.security.AuthContext;
import com.bobo.security.JwtUtil;
import com.bobo.security.PasswordEncoder;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证与账号服务：注册、登录、资料、密码、学生认证、个人概览。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final ItemMapper itemMapper;
    private final OrderMapper orderMapper;
    private final FavoriteMapper favoriteMapper;
    private final ConversationMapper conversationMapper;
    private final NoticeMapper noticeMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final BoboProperties properties;

    /** 注册，返回与登录一致的结构，注册完直接进站 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> register(Dtos.RegisterDTO dto) {
        if (userMapper.selectByUsername(dto.username()) != null) {
            throw new BizException("该账号已被注册");
        }

        boolean admin = false;
        if (StringUtils.hasText(dto.adminCode())) {
            String expected = properties.getAdmin().getRegisterCode();
            if (!StringUtils.hasText(expected)) {
                throw new BizException("本部署未开放管理员注册");
            }
            if (!expected.equals(dto.adminCode())) {
                throw new BizException("管理员邀请码不正确");
            }
            admin = true;
        }

        User user = new User();
        user.setUsername(dto.username().trim());
        user.setPassword(passwordEncoder.encode(dto.password()));
        user.setNickname(StringUtils.hasText(dto.nickname()) ? dto.nickname().trim() : dto.username());
        user.setPhone(emptyToNull(dto.phone()));
        user.setEmail(emptyToNull(dto.email()));
        user.setCampus(emptyToNull(dto.campus()));
        user.setCollege(emptyToNull(dto.college()));
        user.setGrade(emptyToNull(dto.grade()));
        user.setStudentNo(emptyToNull(dto.studentNo()));
        user.setCreditScore(100);
        user.setRole(admin ? User.ROLE_ADMIN : User.ROLE_USER);
        user.setVerifyStatus(User.VERIFY_NONE);
        user.setStatus(User.STATUS_NORMAL);
        user.setDelFlag(0);
        userMapper.insert(user);

        return buildLoginResult(user);
    }

    /** 登录 */
    public Map<String, Object> login(Dtos.LoginDTO dto, HttpServletRequest request) {
        User user = userMapper.selectByUsername(dto.username().trim());
        // 账号不存在与密码错误返回同一句提示，避免被用来枚举账号
        if (user == null || !passwordEncoder.matches(dto.password(), user.getPassword())) {
            throw new BizException("账号或密码不正确");
        }
        if (user.isBanned()) {
            throw new BizException("账号已被封禁，请联系管理员");
        }
        userMapper.updateLoginInfo(user.getUserId(), clientIp(request));
        user.setLoginTime(LocalDateTime.now());
        return buildLoginResult(user);
    }

    /** 当前登录用户实体 */
    public User currentUser() {
        User user = userMapper.selectById(AuthContext.userId());
        if (user == null) {
            throw BizException.unauthorized("账号不存在或已被删除");
        }
        return user;
    }

    /** 更新资料：只更新请求里出现的字段 */
    @Transactional(rollbackFor = Exception.class)
    public User updateProfile(Dtos.ProfileDTO dto) {
        User user = currentUser();
        if (StringUtils.hasText(dto.nickname())) {
            user.setNickname(dto.nickname().trim());
        }
        if (dto.avatar() != null) {
            user.setAvatar(emptyToNull(dto.avatar()));
        }
        if (dto.phone() != null) {
            user.setPhone(emptyToNull(dto.phone()));
        }
        if (dto.email() != null) {
            user.setEmail(emptyToNull(dto.email()));
        }
        if (dto.campus() != null) {
            user.setCampus(emptyToNull(dto.campus()));
        }
        if (dto.college() != null) {
            user.setCollege(emptyToNull(dto.college()));
        }
        if (dto.grade() != null) {
            user.setGrade(emptyToNull(dto.grade()));
        }
        if (dto.studentNo() != null) {
            user.setStudentNo(emptyToNull(dto.studentNo()));
        }
        if (dto.bio() != null) {
            user.setBio(emptyToNull(dto.bio()));
        }
        userMapper.updateById(user);
        return user;
    }

    /** 修改密码 */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Dtos.ChangePasswordDTO dto) {
        User user = currentUser();
        if (!passwordEncoder.matches(dto.oldPassword(), user.getPassword())) {
            throw new BizException("原密码不正确");
        }
        if (dto.oldPassword().equals(dto.newPassword())) {
            throw new BizException("新密码不能与原密码相同");
        }
        User update = new User();
        update.setUserId(user.getUserId());
        update.setPassword(passwordEncoder.encode(dto.newPassword()));
        userMapper.updateById(update);
    }

    /** 提交学生认证：进入「认证中」，由管理员审核 */
    @Transactional(rollbackFor = Exception.class)
    public void submitVerify(Dtos.VerifyDTO dto) {
        User user = currentUser();
        if (user.getVerifyStatus() != null && user.getVerifyStatus() == User.VERIFY_PASSED) {
            throw new BizException("已完成学生认证，无需重复提交");
        }
        user.setStudentNo(dto.studentNo().trim());
        user.setCollege(dto.college().trim());
        user.setVerifyStatus(User.VERIFY_PENDING);
        userMapper.updateById(user);
    }

    /** 个人中心概览（角标统计） */
    public Map<String, Object> overview() {
        Long userId = AuthContext.userId();
        Map<String, Object> map = new HashMap<>(12);
        map.put("itemCount", itemMapper.selectCount(
                Wrappers.<Item>lambdaQuery().eq(Item::getSellerId, userId)));
        map.put("onSaleCount", itemMapper.selectCount(
                Wrappers.<Item>lambdaQuery().eq(Item::getSellerId, userId).eq(Item::getStatus, Item.STATUS_ON_SALE)));
        map.put("soldCount", itemMapper.selectCount(
                Wrappers.<Item>lambdaQuery().eq(Item::getSellerId, userId).eq(Item::getStatus, Item.STATUS_SOLD)));
        map.put("orderBuyCount", orderMapper.selectCount(
                Wrappers.<Order>lambdaQuery().eq(Order::getBuyerId, userId)));
        map.put("orderSellCount", orderMapper.selectCount(
                Wrappers.<Order>lambdaQuery().eq(Order::getSellerId, userId)));
        map.put("favoriteCount", favoriteMapper.selectCount(
                Wrappers.<Favorite>lambdaQuery().eq(Favorite::getUserId, userId)));
        map.put("unreadMessage", conversationMapper.countUnread(userId));
        map.put("unreadNotice", noticeMapper.selectCount(
                Wrappers.<Notice>lambdaQuery()
                        .eq(Notice::getIsRead, 0)
                        .and(w -> w.eq(Notice::getUserId, userId).or().eq(Notice::getUserId, Notice.USER_ALL))));
        return map;
    }

    /** 用户公开信息，不存在时抛 404 */
    public SimpleVos.UserVO publicUser(Long userId) {
        SimpleVos.UserVO vo = userMapper.selectUserVO(userId);
        if (vo == null) {
            throw BizException.notFound("用户不存在");
        }
        return vo;
    }

    private Map<String, Object> buildLoginResult(User user) {
        String token = jwtUtil.createToken(user.getUserId(), user.getUsername(), user.isAdminUser());
        Map<String, Object> result = new HashMap<>(4);
        result.put("token", token);
        result.put("expiresIn", jwtUtil.getExpireMillis() / 1000);
        result.put("user", toVO(user));
        return result;
    }

    /** 用户实体 → 公开 VO（不含密码） */
    public SimpleVos.UserVO toVO(User user) {
        SimpleVos.UserVO vo = new SimpleVos.UserVO();
        vo.setUserId(user.getUserId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setCampus(user.getCampus());
        vo.setCollege(user.getCollege());
        vo.setGrade(user.getGrade());
        vo.setBio(user.getBio());
        vo.setCreditScore(user.getCreditScore());
        vo.setRole(user.getRole());
        vo.setVerifyStatus(user.getVerifyStatus());
        vo.setStatus(user.getStatus());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP"};
        for (String header : headers) {
            String value = request.getHeader(header);
            if (StringUtils.hasText(value) && !"unknown".equalsIgnoreCase(value)) {
                int comma = value.indexOf(',');
                return comma > 0 ? value.substring(0, comma).trim() : value.trim();
            }
        }
        return request.getRemoteAddr();
    }

    private String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
