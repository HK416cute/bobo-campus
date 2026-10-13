package com.bobo.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bobo.common.PageResult;
import com.bobo.common.exception.BizException;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.entity.Item;
import com.bobo.domain.entity.ItemImage;
import com.bobo.domain.entity.User;
import com.bobo.domain.query.ItemQuery;
import com.bobo.domain.vo.ItemCardVO;
import com.bobo.domain.vo.ItemDetailVO;
import com.bobo.mapper.FavoriteMapper;
import com.bobo.mapper.ItemImageMapper;
import com.bobo.mapper.ItemMapper;
import com.bobo.mapper.UserMapper;
import com.bobo.security.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 商品服务：发布、编辑、审核流转、下架、删除。
 *
 * @author bobo
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItemService {

    private static final int MAX_IMAGES = 9;
    private static final int SELLER_OTHER_LIMIT = 6;

    private final ItemMapper itemMapper;
    private final ItemImageMapper itemImageMapper;
    private final FavoriteMapper favoriteMapper;
    private final UserMapper userMapper;

    /**
     * NotificationService 依赖 AuthService，而本类也需要通知能力；
     * 这里用 @Lazy 断开构造期的环，避免 Spring 启动时报循环依赖。
     */
    @Lazy
    @Autowired
    private NotificationService notificationService;

    // ------------------------------------------------------------------ 查询

    /** 商品分页（前台搜索 / 卖家主页 / 后台管理共用） */
    public PageResult<ItemCardVO> page(ItemQuery query) {
        // 前台默认只看在售；管理员不传 status 时看全部
        if (query.getStatus() == null && !AuthContext.isAdmin()) {
            query.setStatus(Item.STATUS_ON_SALE);
        }
        Page<ItemCardVO> page = new Page<>(query.safePageNum(), query.safePageSize());
        IPage<ItemCardVO> result = itemMapper.selectItemPage(page, query, AuthContext.userIdOrNull());
        return PageResult.of(result);
    }

    /** 商品详情：浏览量 +1，并带回卖家其他在售商品 */
    @Transactional(rollbackFor = Exception.class)
    public ItemDetailVO detail(Long itemId) {
        Long currentUserId = AuthContext.userIdOrNull();
        ItemDetailVO vo = itemMapper.selectItemDetail(itemId, currentUserId);
        if (vo == null) {
            throw BizException.notFound("商品不存在或已被删除");
        }
        // 非本人、非管理员只能看「在售 / 已售」
        boolean owner = currentUserId != null && currentUserId.equals(vo.getSellerId());
        if (!owner && !AuthContext.isAdmin()
                && vo.getStatus() != Item.STATUS_ON_SALE && vo.getStatus() != Item.STATUS_SOLD) {
            throw BizException.notFound("该商品已下架");
        }

        vo.setImages(itemImageMapper.selectUrlsByItemId(itemId));
        if (vo.getImages().isEmpty() && StringUtils.hasText(vo.getCover())) {
            vo.setImages(List.of(vo.getCover()));
        }
        vo.setSellerOtherItems(itemMapper.selectSellerOtherItems(vo.getSellerId(), itemId, SELLER_OTHER_LIMIT));

        itemMapper.increaseViewCount(itemId);
        vo.setViewCount(vo.getViewCount() == null ? 1 : vo.getViewCount() + 1);
        return vo;
    }

    /** 卡片列表（收藏、订单等场景复用） */
    public List<ItemCardVO> cardsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return itemMapper.selectCardsByIds(ids.stream().distinct().toList());
    }

    // ------------------------------------------------------------------ 写操作

    /** 发布商品：统一进入待审核 */
    @Transactional(rollbackFor = Exception.class)
    public Long publish(Dtos.ItemDTO dto) {
        Long userId = AuthContext.userId();
        User user = userMapper.selectById(userId);
        if (user != null && user.isBanned()) {
            throw BizException.forbidden("账号已被封禁，无法发布商品");
        }
        if (hasPendingItem(userId)) {
            throw new BizException("你还有商品正在审核中，请等待审核通过后再发布");
        }

        Item item = new Item();
        fill(item, dto);
        item.setSellerId(userId);
        item.setCampus(StringUtils.hasText(dto.campus()) ? dto.campus().trim()
                : (user == null ? null : user.getCampus()));
        item.setStatus(Item.STATUS_PENDING);
        item.setViewCount(0);
        item.setWantCount(0);
        item.setDelFlag(0);

        List<String> images = normalizeImages(dto.images());
        item.setCover(images.isEmpty() ? null : images.get(0));
        itemMapper.insert(item);

        saveImages(item.getItemId(), images);
        return item.getItemId();
    }

    /** 编辑商品：只有本人可改，改完重新进入待审核 */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long itemId, Dtos.ItemDTO dto) {
        Item item = requireOwned(itemId);
        if (item.getStatus() == Item.STATUS_SOLD) {
            throw new BizException("商品已售出，无法编辑");
        }

        fill(item, dto);
        if (StringUtils.hasText(dto.campus())) {
            item.setCampus(dto.campus().trim());
        }

        List<String> images = normalizeImages(dto.images());
        if (!images.isEmpty()) {
            item.setCover(images.get(0));
        }

        // 编辑后需要重新审核：从「在售」回到「待审核」
        if (item.getStatus() == Item.STATUS_ON_SALE || item.getStatus() == Item.STATUS_REJECTED) {
            item.setStatus(Item.STATUS_PENDING);
            item.setRejectReason(null);
        }
        itemMapper.updateById(item);

        if (!images.isEmpty()) {
            itemImageMapper.deleteByItemId(itemId);
            saveImages(itemId, images);
        }
    }

    /** 本人下架 */
    @Transactional(rollbackFor = Exception.class)
    public void offShelfByOwner(Long itemId) {
        Item item = requireOwned(itemId);
        if (item.getStatus() == Item.STATUS_SOLD) {
            throw new BizException("商品已售出，无法下架");
        }
        item.setStatus(Item.STATUS_OFF_SHELF);
        itemMapper.updateById(item);
    }

    /** 本人重新上架：回到待审核，由管理员再次把关 */
    @Transactional(rollbackFor = Exception.class)
    public void onShelfByOwner(Long itemId) {
        Item item = requireOwned(itemId);
        if (item.getStatus() == Item.STATUS_ON_SALE) {
            throw new BizException("商品已在售卖中");
        }
        if (item.getStatus() == Item.STATUS_SOLD) {
            throw new BizException("商品已售出，无法重新上架");
        }
        item.setStatus(Item.STATUS_PENDING);
        item.setRejectReason(null);
        itemMapper.updateById(item);
    }

    /** 删除商品：本人或管理员，逻辑删除并清理图片与收藏 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long itemId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw BizException.notFound("商品不存在");
        }
        Long userId = AuthContext.userId();
        if (!AuthContext.isAdmin() && !userId.equals(item.getSellerId())) {
            throw BizException.forbidden("只能删除自己发布的商品");
        }
        itemMapper.deleteById(itemId);
        itemImageMapper.deleteByItemId(itemId);
        favoriteMapper.deleteByItem(itemId);
    }

    /** 审核（管理员） */
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long itemId, Dtos.AuditDTO dto) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw BizException.notFound("商品不存在");
        }
        if (dto.pass()) {
            item.setStatus(Item.STATUS_ON_SALE);
            item.setRejectReason(null);
        } else {
            if (!StringUtils.hasText(dto.reason())) {
                throw new BizException("驳回时必须填写原因");
            }
            item.setStatus(Item.STATUS_REJECTED);
            item.setRejectReason(dto.reason().trim());
        }
        item.setAuditTime(java.time.LocalDateTime.now());
        item.setAuditBy(AuthContext.userId());
        itemMapper.updateById(item);

        notificationService.notify(dto.pass() ? "商品审核通过" : "商品审核未通过",
                dto.pass() ? "你的商品「" + item.getTitle() + "」已上架，快去看看吧"
                        : "你的商品「" + item.getTitle() + "」未通过审核：" + dto.reason(),
                dto.pass() ? com.bobo.domain.entity.Notice.TYPE_AUDIT : com.bobo.domain.entity.Notice.TYPE_AUDIT,
                item.getItemId(), item.getSellerId());
    }

    /** 强制下架（管理员） */
    @Transactional(rollbackFor = Exception.class)
    public void offShelfByAdmin(Long itemId, String reason) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw BizException.notFound("商品不存在");
        }
        item.setStatus(Item.STATUS_OFF_SHELF);
        if (StringUtils.hasText(reason)) {
            item.setRejectReason(reason.trim());
        }
        itemMapper.updateById(item);
        notificationService.notify("商品已被下架",
                "你的商品「" + item.getTitle() + "」被管理员下架"
                        + (StringUtils.hasText(reason) ? "，原因：" + reason : ""),
                com.bobo.domain.entity.Notice.TYPE_AUDIT, item.getItemId(), item.getSellerId());
    }

    /** 恢复上架（管理员） */
    @Transactional(rollbackFor = Exception.class)
    public void onShelfByAdmin(Long itemId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw BizException.notFound("商品不存在");
        }
        item.setStatus(Item.STATUS_ON_SALE);
        item.setAuditTime(java.time.LocalDateTime.now());
        item.setAuditBy(AuthContext.userId());
        itemMapper.updateById(item);
    }

    /** 批量通过审核 */
    @Transactional(rollbackFor = Exception.class)
    public int batchAudit(List<Long> ids) {
        int count = 0;
        for (Long id : ids) {
            Item item = itemMapper.selectById(id);
            if (item == null) {
                continue;
            }
            item.setStatus(Item.STATUS_ON_SALE);
            item.setRejectReason(null);
            item.setAuditTime(java.time.LocalDateTime.now());
            item.setAuditBy(AuthContext.userId());
            itemMapper.updateById(item);
            notificationService.notify("商品审核通过",
                    "你的商品「" + item.getTitle() + "」已上架", com.bobo.domain.entity.Notice.TYPE_AUDIT,
                    item.getItemId(), item.getSellerId());
            count++;
        }
        return count;
    }

    /** 批量下架 */
    @Transactional(rollbackFor = Exception.class)
    public int batchOffShelf(List<Long> ids) {
        int count = 0;
        for (Long id : ids) {
            Item item = itemMapper.selectById(id);
            if (item == null) {
                continue;
            }
            item.setStatus(Item.STATUS_OFF_SHELF);
            itemMapper.updateById(item);
            count++;
        }
        return count;
    }

    /** 商品被下单后置为已售（由 OrderService 调用） */
    @Transactional(rollbackFor = Exception.class)
    public void markSold(Long itemId) {
        itemMapper.updateStatus(itemId, Item.STATUS_SOLD);
    }

    /** 退款 / 取消后恢复在售 */
    @Transactional(rollbackFor = Exception.class)
    public void restoreOnSale(Long itemId) {
        Item item = itemMapper.selectById(itemId);
        if (item != null && item.getStatus() == Item.STATUS_SOLD) {
            item.setStatus(Item.STATUS_ON_SALE);
            item.setSoldTime(null);
            itemMapper.updateById(item);
        }
    }

    // ------------------------------------------------------------------ 内部方法

    /** 取商品并校验归属当前登录用户 */
    public Item requireOwned(Long itemId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw BizException.notFound("商品不存在");
        }
        if (!AuthContext.userId().equals(item.getSellerId())) {
            throw BizException.forbidden("只能操作自己发布的商品");
        }
        return item;
    }

    private void fill(Item item, Dtos.ItemDTO dto) {
        item.setTitle(dto.title().trim());
        item.setDescription(dto.description());
        item.setPrice(dto.price());
        item.setOriginalPrice(validateOriginalPrice(dto.price(), dto.originalPrice()));
        item.setCategoryId(dto.categoryId());
        item.setConditionLevel(StringUtils.hasText(dto.conditionLevel()) ? dto.conditionLevel().trim() : "95新");
        item.setTradeType(dto.tradeType() == null ? Item.TRADE_BOTH : dto.tradeType());
        item.setLocation(dto.location());
    }

    private BigDecimal validateOriginalPrice(BigDecimal price, BigDecimal originalPrice) {
        if (originalPrice == null) {
            return null;
        }
        if (originalPrice.compareTo(price) < 0) {
            throw new BizException("原价不能低于售价");
        }
        return originalPrice;
    }

    /** 去重、限张数，保持前端选择的顺序（第一张即封面） */
    private List<String> normalizeImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String url : images) {
            if (StringUtils.hasText(url)) {
                set.add(url.trim());
            }
        }
        List<String> list = new ArrayList<>(set);
        if (list.size() > MAX_IMAGES) {
            list = list.subList(0, MAX_IMAGES);
        }
        return list;
    }

    private void saveImages(Long itemId, List<String> images) {
        if (images.isEmpty()) {
            return;
        }
        for (int i = 0; i < images.size(); i++) {
            ItemImage image = new ItemImage();
            image.setItemId(itemId);
            image.setUrl(images.get(i));
            image.setSort(i);
            itemImageMapper.insert(image);
        }
    }

    /** 是否已有待审核商品（限制刷屏式发布） */
    private boolean hasPendingItem(Long userId) {
        Long count = itemMapper.selectCount(Wrappers.<Item>lambdaQuery()
                .eq(Item::getSellerId, userId)
                .eq(Item::getStatus, Item.STATUS_PENDING));
        return count != null && count > 0;
    }
}
