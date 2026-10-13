package com.bobo.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bobo.common.exception.BizException;
import com.bobo.domain.dto.Dtos;
import com.bobo.domain.entity.Address;
import com.bobo.mapper.AddressMapper;
import com.bobo.security.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收货地址服务。
 *
 * @author bobo
 */
@Service
@RequiredArgsConstructor
public class AddressService {

    private final AddressMapper addressMapper;

    /** 我的地址列表，默认地址排最前 */
    public List<Address> listMine() {
        return addressMapper.selectList(Wrappers.<Address>lambdaQuery()
                .eq(Address::getUserId, AuthContext.userId())
                .orderByDesc(Address::getIsDefault)
                .orderByDesc(Address::getCreateTime));
    }

    @Transactional(rollbackFor = Exception.class)
    public Address create(Dtos.AddressDTO dto) {
        Long userId = AuthContext.userId();
        Address address = new Address();
        address.setUserId(userId);
        apply(address, dto);

        boolean first = addressMapper.selectCount(Wrappers.<Address>lambdaQuery()
                .eq(Address::getUserId, userId)) == 0;
        // 第一条地址自动成为默认地址
        boolean makeDefault = first || (dto.isDefault() != null && dto.isDefault() == 1);
        if (makeDefault) {
            addressMapper.clearDefault(userId);
            address.setIsDefault(1);
        }
        address.setDelFlag(0);
        addressMapper.insert(address);
        return address;
    }

    @Transactional(rollbackFor = Exception.class)
    public Address update(Long addressId, Dtos.AddressDTO dto) {
        Address address = requireOwn(addressId);
        apply(address, dto);
        if (dto.isDefault() != null && dto.isDefault() == 1) {
            addressMapper.clearDefault(address.getUserId());
            address.setIsDefault(1);
        }
        addressMapper.updateById(address);
        return address;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long addressId) {
        Address address = requireOwn(addressId);
        addressMapper.deleteById(addressId);
        // 删掉的是默认地址时，把剩下最新的一条补为默认，避免下单时没有默认地址
        if (address.getIsDefault() != null && address.getIsDefault() == 1) {
            List<Address> rest = listMine();
            if (!rest.isEmpty()) {
                setDefault(rest.get(0).getAddressId());
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long addressId) {
        Address address = requireOwn(addressId);
        addressMapper.clearDefault(address.getUserId());
        address.setIsDefault(1);
        addressMapper.updateById(address);
    }

    /** 取地址并校验归属 */
    public Address requireOwn(Long addressId) {
        Address address = addressMapper.selectById(addressId);
        if (address == null) {
            throw BizException.notFound("收货地址不存在");
        }
        if (!AuthContext.userId().equals(address.getUserId())) {
            throw BizException.forbidden("只能操作自己的收货地址");
        }
        return address;
    }

    private void apply(Address address, Dtos.AddressDTO dto) {
        address.setReceiver(dto.receiver().trim());
        address.setPhone(dto.phone().trim());
        address.setCampus(dto.campus());
        address.setDetail(dto.detail().trim());
        if (address.getIsDefault() == null) {
            address.setIsDefault(dto.isDefault() == null ? 0 : dto.isDefault());
        }
    }
}
