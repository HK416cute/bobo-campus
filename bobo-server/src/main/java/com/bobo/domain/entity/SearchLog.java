package com.bobo.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 搜索关键词日志：只用于统计首页「热门搜索」，不保存搜索结果或用户隐私内容。
 *
 * <p>记录时机：前台商品搜索第一页、关键词非空且非管理员。分页翻页不重复记录，
 * 避免同一用户连续翻页把某个词刷到榜首。
 *
 * @author bobo
 */
@Data
@TableName("bobo_search_log")
public class SearchLog implements Serializable {

    @TableId(value = "log_id", type = IdType.AUTO)
    private Long logId;

    /** 归一化后的关键词（trim + 合并空白，最长 64） */
    private String keyword;

    /** 搜索用户，匿名可为空 */
    private Long userId;

    /** 来源：search 普通搜索 / nl 自然语言搜索 */
    private String source;

    private LocalDateTime createTime;
}
