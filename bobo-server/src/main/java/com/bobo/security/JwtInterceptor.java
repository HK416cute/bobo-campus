package com.bobo.security;

import com.bobo.common.Result;
import com.bobo.config.props.BoboProperties;
import com.bobo.domain.entity.User;
import com.bobo.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * JWT 登录拦截器。
 * <p>
 * 职责：
 * <ol>
 *   <li>解析 Authorization 头里的 Bearer token，写入 {@link AuthContext}；</li>
 *   <li>带 {@link Anonymous} 的接口跳过校验（但仍然会解析 token，便于"登录可选"的接口识别身份）；</li>
 *   <li>统一拦截 /api/admin/** 的管理员权限，避免每个 Controller 重复写校验；</li>
 *   <li>每次请求都回查一次用户，保证封禁 / 删除能立刻生效（本项目不做 token 黑名单）。</li>
 * </ol>
 *
 * @author bobo
 */
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final BoboProperties properties;
    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        // 预检请求直接放过，交给 CorsFilter 处理
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        boolean anonymous = isAnonymous(handler);
        String token = resolveToken(request);

        if (StringUtils.hasText(token)) {
            Claims claims = jwtUtil.parse(token);
            if (claims != null) {
                Long userId = Long.valueOf(claims.getSubject());
                User user = userMapper.selectById(userId);
                if (user != null && !user.isBanned()) {
                    AuthContext.set(new AuthContext.LoginUser(userId, user.getUsername(), user.isAdminUser()));
                }
            }
        }

        boolean logged = AuthContext.get() != null;

        if (!logged && !anonymous) {
            write(response, Result.CODE_UNAUTHORIZED, "登录状态已失效，请重新登录");
            return false;
        }

        // 管理端统一权限闸门
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/admin/") && !AuthContext.isAdmin()) {
            write(response, logged ? Result.CODE_FORBIDDEN : Result.CODE_UNAUTHORIZED,
                    logged ? "需要管理员权限" : "登录状态已失效，请重新登录");
            return false;
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }

    private boolean isAnonymous(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            // 静态资源 / 错误页等
            return true;
        }
        return method.hasMethodAnnotation(Anonymous.class)
                || method.getBeanType().isAnnotationPresent(Anonymous.class);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(properties.getJwt().getHeader());
        if (!StringUtils.hasText(header)) {
            return null;
        }
        String prefix = properties.getJwt().getPrefix();
        if (StringUtils.hasText(prefix) && header.startsWith(prefix)) {
            return header.substring(prefix.length()).trim();
        }
        return header.trim();
    }

    private void write(HttpServletResponse response, int code, String msg) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(code, msg)));
    }
}
