package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.ItemImage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商品图片 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface ItemImageMapper extends BaseMapper<ItemImage> {

    @Select("SELECT url FROM bobo_item_image WHERE item_id = #{itemId} ORDER BY sort, image_id")
    List<String> selectUrlsByItemId(@Param("itemId") Long itemId);

    @Delete("DELETE FROM bobo_item_image WHERE item_id = #{itemId}")
    int deleteByItemId(@Param("itemId") Long itemId);
}
