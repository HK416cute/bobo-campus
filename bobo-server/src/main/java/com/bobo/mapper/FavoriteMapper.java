package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.Favorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 收藏 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface FavoriteMapper extends BaseMapper<Favorite> {

    /** 物理删除（收藏不需要软删除，避免唯一键与软删标记互相打架） */
    @org.apache.ibatis.annotations.Delete("DELETE FROM bobo_favorite WHERE user_id = #{userId} AND item_id = #{itemId}")
    int deleteByUserAndItem(@Param("userId") Long userId, @Param("itemId") Long itemId);

    @Select("SELECT COUNT(*) FROM bobo_favorite WHERE user_id = #{userId} AND item_id = #{itemId}")
    int countByUserAndItem(@Param("userId") Long userId, @Param("itemId") Long itemId);

    /** 某个商品的收藏总数（用于校准 want_count） */
    @Select("SELECT COUNT(*) FROM bobo_favorite WHERE item_id = #{itemId}")
    int countByItem(@Param("itemId") Long itemId);

    /** 商品被删除时清理收藏关系 */
    @Update("DELETE FROM bobo_favorite WHERE item_id = #{itemId}")
    int deleteByItem(@Param("itemId") Long itemId);

    /** 收藏总量（后台概览） */
    @Select("SELECT COUNT(*) FROM bobo_favorite")
    long countAll();
}
