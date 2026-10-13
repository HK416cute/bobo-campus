package com.bobo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bobo.domain.entity.Address;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 收货地址 Mapper。
 *
 * @author bobo
 */
@Mapper
public interface AddressMapper extends BaseMapper<Address> {

    /** 取消该用户其它地址的默认标记 */
    @Update("UPDATE bobo_address SET is_default = 0 WHERE user_id = #{userId} AND del_flag = 0")
    int clearDefault(@Param("userId") Long userId);
}
