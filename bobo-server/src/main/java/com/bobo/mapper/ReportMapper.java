package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.bobo.domain.entity.Report;
import com.bobo.domain.query.ReportQuery;
import com.bobo.domain.vo.SimpleVos;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 举报 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface ReportMapper extends BaseMapper<Report> {

    IPage<SimpleVos.ReportVO> selectReportPage(IPage<SimpleVos.ReportVO> page,
                                               @Param("q") ReportQuery query);

    /** 待处理举报数（后台红点） */
    @Select("SELECT COUNT(*) FROM bobo_report WHERE del_flag = 0 AND status = 0")
    long countPending();
}
