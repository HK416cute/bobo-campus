package com.bobo.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bobo.common.exception.BizException;
import com.bobo.domain.entity.Category;
import com.bobo.domain.vo.SimpleVos;
import com.bobo.mapper.CategoryMapper;
import com.bobo.mapper.ItemMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分类服务。
 *
 * @author bobo
 */
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryMapper categoryMapper;
    private final ItemMapper itemMapper;

    /** 启用中的分类（含在售数量），前台导航用 */
    public List<SimpleVos.CategoryVO> listEnabled() {
        List<Category> categories = categoryMapper.selectList(
                Wrappers.<Category>lambdaQuery()
                        .eq(Category::getStatus, Category.STATUS_ENABLED)
                        .orderByAsc(Category::getSort, Category::getCategoryId));

        Map<Long, Long> counts = onSaleCountByCategory();
        List<SimpleVos.CategoryVO> result = new ArrayList<>(categories.size());
        for (Category c : categories) {
            result.add(toVO(c, counts.getOrDefault(c.getCategoryId(), 0L)));
        }
        return result;
    }

    /** 全部分类（含停用），后台管理用 */
    public List<SimpleVos.CategoryVO> listAll() {
        List<Category> categories = categoryMapper.selectList(
                Wrappers.<Category>lambdaQuery().orderByAsc(Category::getSort, Category::getCategoryId));
        Map<Long, Long> counts = onSaleCountByCategory();
        List<SimpleVos.CategoryVO> result = new ArrayList<>(categories.size());
        for (Category c : categories) {
            result.add(toVO(c, counts.getOrDefault(c.getCategoryId(), 0L)));
        }
        return result;
    }

    public Category require(Long categoryId) {
        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw BizException.notFound("分类不存在");
        }
        return category;
    }

    /** 新增分类 */
    public Category create(com.bobo.domain.dto.Dtos.CategoryDTO dto) {
        Category category = new Category();
        category.setParentId(dto.parentId() == null ? 0L : dto.parentId());
        category.setName(dto.name().trim());
        category.setIcon(dto.icon());
        category.setSort(dto.sort() == null ? 99 : dto.sort());
        category.setStatus(dto.status() == null ? Category.STATUS_ENABLED : dto.status());
        category.setDelFlag(0);
        categoryMapper.insert(category);
        return category;
    }

    /** 编辑分类 */
    public Category update(Long categoryId, com.bobo.domain.dto.Dtos.CategoryDTO dto) {
        Category category = require(categoryId);
        category.setName(dto.name().trim());
        if (dto.parentId() != null) {
            category.setParentId(dto.parentId());
        }
        if (dto.icon() != null) {
            category.setIcon(dto.icon());
        }
        if (dto.sort() != null) {
            category.setSort(dto.sort());
        }
        if (dto.status() != null) {
            category.setStatus(dto.status());
        }
        categoryMapper.updateById(category);
        return category;
    }

    /** 删除分类：该分类下还有商品时拒绝，避免商品挂在已删除的分类上 */
    public void delete(Long categoryId) {
        require(categoryId);
        Long itemCount = itemMapper.selectCount(
                Wrappers.<com.bobo.domain.entity.Item>lambdaQuery()
                        .eq(com.bobo.domain.entity.Item::getCategoryId, categoryId));
        if (itemCount != null && itemCount > 0) {
            throw new BizException("该分类下还有 " + itemCount + " 件商品，请先转移或删除后再试");
        }
        Long childCount = categoryMapper.selectCount(
                Wrappers.<Category>lambdaQuery().eq(Category::getParentId, categoryId));
        if (childCount != null && childCount > 0) {
            throw new BizException("请先删除该分类下的子分类");
        }
        categoryMapper.deleteById(categoryId);
    }

    /** 分类 → JSON（供前端下拉使用） */
    public List<Map<String, Object>> options() {
        List<Category> categories = categoryMapper.selectList(
                Wrappers.<Category>lambdaQuery().orderByAsc(Category::getSort));
        List<Map<String, Object>> list = new ArrayList<>(categories.size());
        for (Category c : categories) {
            Map<String, Object> map = new HashMap<>(3);
            map.put("value", c.getCategoryId());
            map.put("label", c.getName());
            map.put("icon", c.getIcon());
            list.add(map);
        }
        return list;
    }

    /** 每个分类的在售商品数 */
    private Map<Long, Long> onSaleCountByCategory() {
        List<Map<String, Object>> rows = itemMapper.countByCategory();
        Map<Long, Long> counts = new HashMap<>(rows.size() * 2);
        for (Map<String, Object> row : rows) {
            Object id = row.get("categoryId");
            Object total = row.get("total");
            if (id != null) {
                counts.put(((Number) id).longValue(), total == null ? 0L : ((Number) total).longValue());
            }
        }
        return counts;
    }

    private SimpleVos.CategoryVO toVO(Category c, long itemCount) {
        SimpleVos.CategoryVO vo = new SimpleVos.CategoryVO();
        vo.setCategoryId(c.getCategoryId());
        vo.setParentId(c.getParentId());
        vo.setName(c.getName());
        vo.setIcon(c.getIcon());
        vo.setSort(c.getSort());
        vo.setStatus(c.getStatus());
        vo.setItemCount(itemCount);
        return vo;
    }
}
