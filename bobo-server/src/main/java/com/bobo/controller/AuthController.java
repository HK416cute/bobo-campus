package com.bobo.controller;

import com.bobo.common.Result;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.entity.User;
import com.bobo.security.Anonymous;
import com.bobo.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证与账号接口。
 *
 * @author bobo
 */
@Tag(name = "01 认证与账号")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Anonymous
    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@Valid @RequestBody Dtos.RegisterDTO dto) {
        return Result.ok("注册成功", authService.register(dto));
    }

    @Anonymous
    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody Dtos.LoginDTO dto, HttpServletRequest request) {
        return Result.ok("登录成功", authService.login(dto, request));
    }

    @Operation(summary = "退出登录（前端清 token 即可，这里只做语义占位）")
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.ok();
    }

    @Operation(summary = "当前登录用户")
    @GetMapping("/me")
    public Result<com.bobo.domain.vo.SimpleVos.UserVO> me() {
        User user = authService.currentUser();
        return Result.ok(authService.toVO(user));
    }

    @Operation(summary = "更新个人资料")
    @PutMapping("/profile")
    public Result<com.bobo.domain.vo.SimpleVos.UserVO> updateProfile(@Valid @RequestBody Dtos.ProfileDTO dto) {
        User user = authService.updateProfile(dto);
        return Result.ok("资料已更新", authService.toVO(user));
    }

    @Operation(summary = "修改密码")
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody Dtos.ChangePasswordDTO dto) {
        authService.changePassword(dto);
        return Result.ok("密码已修改，请重新登录", null);
    }

    @Operation(summary = "提交学生认证")
    @PostMapping("/verify")
    public Result<Void> verify(@Valid @RequestBody Dtos.VerifyDTO dto) {
        authService.submitVerify(dto);
        return Result.ok("已提交认证，请等待管理员审核", null);
    }

    @Operation(summary = "个人中心概览")
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.ok(authService.overview());
    }
}
