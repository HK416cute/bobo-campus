package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.bobo.domain.entity.User;
import com.bobo.domain.query.UserQuery;
import com.bobo.domain.vo.SimpleVos;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 用户 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 后台用户列表（带在售商品数、成交数等统计） */
    IPage<SimpleVos.UserVO> selectUserPage(IPage<SimpleVos.UserVO> page,
                                           @Param("q") UserQuery query);

    /** 用户公开主页信息 */
    @Select("""
            SELECT user_id AS userId, username, nickname, avatar, campus, college, grade, bio,
                   credit_score AS creditScore, role, verify_status AS verifyStatus, status,
                   phone, email, create_time AS createTime
              FROM bobo_user
             WHERE user_id = #{userId} AND del_flag = 0
            """)
    SimpleVos.UserVO selectUserVO(@Param("userId") Long userId);

    /** 按登录账号取用户（登录用） */
    @Select("SELECT * FROM bobo_user WHERE username = #{username} AND del_flag = 0 LIMIT 1")
    User selectByUsername(@Param("username") String username);

    /** 记录登录信息 */
    @Update("UPDATE bobo_user SET login_ip = #{ip}, login_time = NOW() WHERE user_id = #{userId}")
    int updateLoginInfo(@Param("userId") Long userId, @Param("ip") String ip);

    /** 调整信用分（限制在 0-150） */
    @Update("UPDATE bobo_user SET credit_score = LEAST(GREATEST(credit_score + #{delta}, 0), 150) WHERE user_id = #{userId}")
    int changeCreditScore(@Param("userId") Long userId, @Param("delta") int delta);

    /** 封禁 / 解封 */
    @Update("UPDATE bobo_user SET status = #{status} WHERE user_id = #{userId}")
    int updateStatus(@Param("userId") Long userId, @Param("status") Integer status);

    /** 全部启用中的用户ID（发布公告时按需使用） */
    @Select("SELECT user_id FROM bobo_user WHERE del_flag = 0 AND status = 0 AND role = 'user'")
    List<Long> selectAllNormalUserIds();
}
