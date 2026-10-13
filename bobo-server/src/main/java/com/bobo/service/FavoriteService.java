package com.bobo.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bobo.common.PageResult;
import com.bobo.common.exception.BizException;
import com.bobo.domain.entity.Favorite;
import com.bobo.domain.entity.Item;
import com.bobo.domain.query.ItemQuery;
import com.bobo.domain.vo.ItemCardVO;
import com.bobo.mapper.FavoriteMapper;
import com.bobo.mapper.ItemMapper;
import com.bobo.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * 收藏（"想要"）服务。
 * <p>
 * 一致性策略：{@code bobo_favorite} 是唯一事实来源，{@code bobo_item.want_count}
 * 是列表排序用的冗余计数。
 * <p>
 * 这里刻意**不**用「按收藏行数重算」来同步冗余计数，因为：
 * <ul>
 *   <li>历史数据或运营导数据时，want_count 可能本身就大于收藏行数（演示种子数据就是这样），
 *       重算会把它改小，用户看到「28 人想要」突然变成「2 人想要」；</li>
 *   <li>重算需要先 SELECT 再 UPDATE，并发下两个请求会互相覆盖。</li>
 * </ul>
 * 因此改为用一条带 GREATEST 保护的原子 UPDATE 做增量，既不会把计数降到 0 以下，
 * 也不会覆盖既有的展示基数。
 *
 * @author bobo
 */
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteMapper favoriteMapper;
    private final ItemMapper itemMapper;

    /** 收藏 / 取消收藏（幂等切换） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> toggle(Long itemId) {
        Long userId = AuthContext.userId();
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw BizException.notFound("商品不存在");
        }
        if (userId.equals(item.getSellerId())) {
            throw new BizException("不能收藏自己发布的商品");
        }

        boolean favorited;
        if (favoriteMapper.countByUserAndItem(userId, itemId) > 0) {
            favoriteMapper.deleteByUserAndItem(userId, itemId);
            favorited = false;
        } else {
            Favorite favorite = new Favorite();
            favorite.setUserId(userId);
            favorite.setItemId(itemId);
            favoriteMapper.insert(favorite);
            favorited = true;
        }

        // 原子增量：+1 收藏 / -1 取消，且不会低于 0（SQL 里的 GREATEST 保证）
        itemMapper.changeWantCount(itemId, favorited ? 1 : -1);

        Item latest = itemMapper.selectById(itemId);
        Map<String, Object> result = new HashMap<>(3);
        result.put("favorited", favorited);
        result.put("wantCount", latest == null || latest.getWantCount() == null ? 0 : latest.getWantCount());
        return result;
    }

    /** 我的收藏分页（复用商品列表的查询，只是强制只返回收藏过的） */
    public PageResult<ItemCardVO> page(ItemQuery query) {
        Long userId = AuthContext.userId();
        query.setOnlyFavorite(true);
        // 收藏列表要能看到「已售」的商品（方便回访），因此不限制状态
        query.setStatus(null);
        Page<ItemCardVO> page = new Page<>(query.safePageNum(), query.safePageSize());
        IPage<ItemCardVO> result = itemMapper.selectItemPage(page, query, userId);
        return PageResult.of(result);
    }

    /** 是否已收藏 */
    public boolean isFavorited(Long itemId) {
        Long userId = AuthContext.userIdOrNull();
        if (userId == null) {
            return false;
        }
        return favoriteMapper.countByUserAndItem(userId, itemId) > 0;
    }

    /** 我的收藏数量 */
    public long countMine() {
        Long count = favoriteMapper.selectCount(
                Wrappers.<Favorite>lambdaQuery().eq(Favorite::getUserId, AuthContext.userId()));
        return count == null ? 0L : count;
    }
}
