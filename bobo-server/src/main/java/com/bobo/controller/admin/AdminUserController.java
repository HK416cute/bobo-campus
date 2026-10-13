package com.bobo.controller.admin;

import com.bobo.common.PageResult;
import com.bobo.common.Result;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.query.UserQuery;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端用户管理：封禁、认证审核、信用分。
 *
 * @author bobo
 */@Tag(name = "22 管理端-用户")
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    @Operation(summary = "用户分页")
    @GetMapping
    public Result<PageResult<SimpleVos.UserVO>> page(UserQuery query) {
        return Result.ok(userService.adminPage(query));
    }

    @Operation(summary = "用户详情（含行为统计）")
    @GetMapping("/{userId}")
    public Result<Map<String, Object>> detail(@PathVariable Long userId) {
        return Result.ok(userService.detail(userId));
    }

    @Operation(summary = "封禁 / 解封")
    @PutMapping("/{userId}/status")
    public Result<Void> changeStatus(@PathVariable Long userId, @RequestBody Dtos.UserManageDTO dto) {
        userService.changeStatus(userId, dto);
        return Result.ok("已更新账号状态", null);
    }

    @Operation(summary = "学生认证审核")
    @PutMapping("/{userId}/verify")
    public Result<Void> auditVerify(@PathVariable Long userId, @RequestBody Dtos.UserManageDTO dto) {
        userService.auditVerify(userId, dto);
        return Result.ok("已更新认证状态", null);
    }

    @Operation(summary = "调整信用分")
    @PutMapping("/{userId}/credit")
    public Result<Void> changeCredit(@PathVariable Long userId, @Valid @RequestBody Dtos.UserManageDTO dto) {
        userService.changeCredit(userId, dto);
        return Result.ok("已调整信用分", null);
    }
}
