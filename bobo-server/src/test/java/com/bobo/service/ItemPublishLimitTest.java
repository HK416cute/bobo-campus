package com.bobo.service;

import com.bobo.common.exception.BizException;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.entity.Item;
import com.bobo.domain.entity.User;
import com.bobo.mapper.FavoriteMapper;
import com.bobo.mapper.ItemImageMapper;
import com.bobo.mapper.ItemMapper;
import com.bobo.mapper.UserMapper;
import com.bobo.security.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 发布限额测试：同一用户**最多同时挂 5 件待审核商品**，第 6 件被拒。
 *
 * <p>为什么这条必须用测试锁住：这个数字被改过两次需求
 * （最初是 1「发一件必须等审完」，现在是 5），而且"限额"有两种容易混淆的解读：
 * <ol>
 *   <li><b>待审核中的数量</b>上限（现在的实现）——审核出结果即释放名额，正常卖家不受影响；</li>
 *   <li>历史总发布量上限——会把老卖家永久锁死，是错的。</li>
 * </ol>
 * 只写代码不写测试的话，下次改需求很容易滑到第 2 种理解，而那种错误在演示时看不出来。
 *
 * <p>顺带验证"审核出结果就释放名额"：把一件改成已通过后，应能再发一件。
 *
 * @author bobo
 */
class ItemPublishLimitTest {

    private ItemMapper itemMapper;
    private UserMapper userMapper;
    private ItemService itemService;

    /** 当前"库里"的待审核数量，由 mock 的 selectCount 返回 */
    private int pendingInDb;

    @BeforeEach
    void setUp() {
        itemMapper = mock(ItemMapper.class);
        userMapper = mock(UserMapper.class);
        ItemImageMapper itemImageMapper = mock(ItemImageMapper.class);
        FavoriteMapper favoriteMapper = mock(FavoriteMapper.class);

        itemService = new ItemService(itemMapper, itemImageMapper, favoriteMapper, userMapper);

        User seller = new User();
        seller.setUserId(2L);
        seller.setStatus(0);
        seller.setCampus("东校区");
        when(userMapper.selectById(anyLong())).thenReturn(seller);

        // 发布时的待审核计数：直接返回当前"库里的待审核数"
        when(itemMapper.selectCount(any())).thenAnswer(inv -> (long) pendingInDb);
        when(itemMapper.insert(any(Item.class))).thenAnswer(inv -> {
            inv.getArgument(0, Item.class).setItemId(100L);
            return 1;
        });

        AuthContext.set(new AuthContext.LoginUser(2L, "bobo", false));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private static Dtos.ItemDTO draft(String title) {
        return new Dtos.ItemDTO(title, "描述", new BigDecimal("99.00"), null, 1L,
                "95新", 0, "东校区", "三食堂门口", java.util.List.of());
    }

    @Test
    @DisplayName("上限是 5：已挂 0~4 件时都能继续发布")
    void allowsUpToFive() {
        for (int pending = 0; pending < ItemService.MAX_PENDING_ITEMS; pending++) {
            pendingInDb = pending;
            assertThat(itemService.publish(draft("第" + (pending + 1) + "件")))
                    .as("已挂 %d 件时应允许发布第 %d 件", pending, pending + 1)
                    .isEqualTo(100L);
        }
    }

    @Test
    @DisplayName("已挂满 5 件时第 6 件被拒，且错误信息说清数量与上限")
    void rejectsSixth() {
        pendingInDb = ItemService.MAX_PENDING_ITEMS;

        assertThatThrownBy(() -> itemService.publish(draft("第6件")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("5")
                .hasMessageContaining("审核");
    }

    @Test
    @DisplayName("限额取值就是 5（改动需求时必须同步改这个断言与文案）")
    void limitIsFive() {
        assertThat(ItemService.MAX_PENDING_ITEMS).isEqualTo(5);
    }

    @Test
    @DisplayName("审核出结果即释放名额：待审核降到 4 后可以再发一件")
    void releasesQuotaAfterAudit() {
        pendingInDb = ItemService.MAX_PENDING_ITEMS;
        assertThatThrownBy(() -> itemService.publish(draft("被拒")))
                .isInstanceOf(BizException.class);

        // 模拟管理员通过/驳回一件：待审核数量减 1
        pendingInDb = ItemService.MAX_PENDING_ITEMS - 1;
        assertThat(itemService.publish(draft("审核后新发")))
                .as("名额应立即可复用")
                .isEqualTo(100L);
    }

    @Test
    @DisplayName("限额只数「待审核」，与历史总发布量无关（防止滑向错误的第 2 种解读）")
    void limitCountsPendingOnly() {
        // 已下架/在售/已售的商品再多也不影响：mock 只按"待审核数"返回
        pendingInDb = 0;
        assertThat(itemService.publish(draft("老卖家新发"))).isEqualTo(100L);
    }
}
