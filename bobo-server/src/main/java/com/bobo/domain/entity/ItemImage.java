package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 商品图片（一个商品多张，sort = 0 为封面）。
 *
 * @author bobo
 */
@Data
@TableName("bobo_item_image")
public class ItemImage implements Serializable {

    @TableId(value = "image_id", type = IdType.AUTO)
    private Long imageId;

    private Long itemId;
    private String url;
    private Integer sort;
    private LocalDateTime createTime;
}
