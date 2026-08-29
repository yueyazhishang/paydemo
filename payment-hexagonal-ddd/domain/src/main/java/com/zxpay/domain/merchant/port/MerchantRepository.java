package com.zxpay.domain.merchant.port;

import com.zxpay.domain.merchant.model.Merchant;
import com.zxpay.domain.merchant.model.MerchantAppId;
import com.zxpay.domain.merchant.model.MerchantId;

import java.util.Optional;

/**
 * 出站端口：商户仓储。
 *
 * <p>商户数据是典型的<b>读多写极少</b>：每笔支付都要读，一年可能改不了几次。
 * 因此实现层几乎必然要做缓存，且缓存失效要能主动触发
 * （运营在后台改了签约关系，不能等 5 分钟 TTL 自然过期）。
 * 这些属于基础设施关注点，领域层只需要这个接口。
 */
public interface MerchantRepository {

    Optional<Merchant> findById(MerchantId merchantId);

    Optional<Merchant> findByAppId(MerchantAppId appId);

    void save(Merchant merchant);
}
