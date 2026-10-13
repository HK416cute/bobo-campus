package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 收货地址（邮寄交易使用）。
 *
 * @author bobo
 */
@Data
@TableName("bobo_address")
public class Address implements Serializable {

    @TableId(value = "address_id", type = IdType.AUTO)
    private Long addressId;

    private Long userId;
    private String receiver;
    private String phone;
    private String campus;
    private String detail;

    /** 0 否 / 1 是 */
    private Integer isDefault;

    @TableLogic
    @JsonIgnore
    private Integer delFlag;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
