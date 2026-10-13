package com.bobo.controller;

import com.bobo.common.PageResult;
import com.bobo.common.Result;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.entity.Address;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.security.Anonymous;
import com.bobo.service.AddressService;
import com.bobo.service.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 收货地址与站内通知接口。
 *
 * @author bobo
 */
@Tag(name = "08 地址与通知")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AddressNoticeController {

    private final AddressService addressService;
    private final NoticeService noticeService;

    // ------------------------------------------------------------------ 地址

    @Operation(summary = "我的收货地址")
    @GetMapping("/addresses")
    public Result<List<Address>> list() {
        return Result.ok(addressService.listMine());
    }

    @Operation(summary = "新增收货地址")
    @PostMapping("/addresses")
    public Result<Address> create(@Valid @RequestBody Dtos.AddressDTO dto) {
        return Result.ok("地址已保存", addressService.create(dto));
    }

    @Operation(summary = "编辑收货地址")
    @PutMapping("/addresses/{addressId}")
    public Result<Address> update(@PathVariable Long addressId, @Valid @RequestBody Dtos.AddressDTO dto) {
        return Result.ok("地址已更新", addressService.update(addressId, dto));
    }

    @Operation(summary = "删除收货地址")
    @DeleteMapping("/addresses/{addressId}")
    public Result<Void> delete(@PathVariable Long addressId) {
        addressService.delete(addressId);
        return Result.ok("地址已删除", null);
    }

    @Operation(summary = "设为默认地址")
    @PutMapping("/addresses/{addressId}/default")
    public Result<Void> setDefault(@PathVariable Long addressId) {
        addressService.setDefault(addressId);
        return Result.ok("已设为默认地址", null);
    }

    // ------------------------------------------------------------------ 通知

    @Operation(summary = "我的通知分页（含全站公告）")
    @GetMapping("/notices")
    public Result<PageResult<SimpleVos.NoticeVO>> notices(@RequestParam(required = false) String type,
                                                          @RequestParam(required = false) Integer isRead,
                                                          @RequestParam(defaultValue = "1") long pageNum,
                                                          @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(noticeService.page(type, isRead, pageNum, pageSize));
    }

    @Anonymous
    @Operation(summary = "全站公告（首页展示）")
    @GetMapping("/notices/broadcast")
    public Result<List<SimpleVos.NoticeVO>> broadcast(@RequestParam(defaultValue = "5") int limit) {
        return Result.ok(noticeService.broadcast(limit));
    }

    @Operation(summary = "未读通知数")
    @GetMapping("/notices/unread-count")
    public Result<Map<String, Object>> unreadCount() {
        return Result.ok(Map.of("count", noticeService.unreadCount()));
    }

    @Operation(summary = "标记单条已读")
    @PutMapping("/notices/{noticeId}/read")
    public Result<Void> read(@PathVariable Long noticeId) {
        noticeService.markRead(noticeId);
        return Result.ok();
    }

    @Operation(summary = "全部标记已读")
    @PutMapping("/notices/read-all")
    public Result<Map<String, Object>> readAll() {
        return Result.ok(Map.of("updated", noticeService.markAllRead()));
    }
}
