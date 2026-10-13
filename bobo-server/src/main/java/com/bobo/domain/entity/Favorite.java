package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 商品收藏（"想要"）。
 *
 * @author bobo
 */
@Data
@TableName("bobo_favorite")
public class Favorite implements Serializable {

    @TableId(value = "favorite_id", type = IdType.AUTO)
    private Long favoriteId;

    private Long userId;
    private Long itemId;
    private LocalDateTime createTime;
}
