package com.bobo.domain.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 商品详情视图。
 *
 * @author bobo
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ItemDetailVO extends ItemCardVO {

    private String description;
    private String sellerCollege;
    private String sellerGrade;
    private String sellerBio;
    private Long sellerItemCount;
    private String rejectReason;

    /** 图片列表，第一张为封面 */
    private List<String> images = new ArrayList<>();

    /** 卖家在售的其他商品 */
    private List<ItemCardVO> sellerOtherItems = new ArrayList<>();
}
