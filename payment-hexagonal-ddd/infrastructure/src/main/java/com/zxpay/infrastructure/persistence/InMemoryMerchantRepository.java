package com.zxpay.infrastructure.persistence;

import com.zxpay.domain.channel.model.ChannelCode;
import com.zxpay.domain.channel.model.PaymentMethod;
import com.zxpay.domain.merchant.model.ChannelContract;
import com.zxpay.domain.merchant.model.Merchant;
import com.zxpay.domain.merchant.model.MerchantApp;
import com.zxpay.domain.merchant.model.MerchantAppId;
import com.zxpay.domain.merchant.model.MerchantId;
import com.zxpay.domain.merchant.model.MerchantStatus;
import com.zxpay.domain.merchant.port.MerchantRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 商户仓储的内存实现，预置两个演示商户。
 *
 * <p>两个商户的签约配置刻意做得不同，用来演示路由的第一层过滤——
 * <b>商户没签约的通道，能力再强也用不了</b>：
 *
 * <ul>
 *   <li>{@code DEMO_CN}：只签了国内三家的部分支付方式。
 *       对它发起 CARD 支付会直接失败在路由阶段（无签约通道），
 *       而不是等打到通道才报「商户号不存在」。</li>
 *   <li>{@code DEMO_GLOBAL}：签了海外四家，且支持 CARD / Apple Pay / PayPal。
 *       同一笔 CARD 支付在这里能正常走通。</li>
 * </ul>
 *
 * <p>生产环境这份数据来自商户中台，且几乎必然要做缓存：
 * 每笔支付都要读商户配置，但配置一年可能改不了几次。
 * 缓存失效要能主动触发——运营在后台改了签约关系，
 * 不能等 5 分钟 TTL 自然过期。
 */
@Repository
public class InMemoryMerchantRepository implements MerchantRepository {

    private final Map<MerchantId, Merchant> merchants = new ConcurrentHashMap<>();
    private final Map<MerchantAppId, MerchantId> appIndex = new ConcurrentHashMap<>();

    public InMemoryMerchantRepository() {
        seedDomesticMerchant();
        seedGlobalMerchant();
    }

    @Override
    public Optional<Merchant> findById(MerchantId merchantId) {
        return Optional.ofNullable(merchants.get(merchantId));
    }

    @Override
    public Optional<Merchant> findByAppId(MerchantAppId appId) {
        MerchantId merchantId = appIndex.get(appId);
        return merchantId == null ? Optional.empty() : findById(merchantId);
    }

    @Override
    public void save(Merchant merchant) {
        merchants.put(merchant.id(), merchant);
        merchant.apps().forEach(app -> appIndex.put(app.appId(), merchant.id()));
    }

    // =====================================================================
    // 演示数据
    // =====================================================================

    private void seedDomesticMerchant() {
        MerchantAppId appId = MerchantAppId.of("APP00000000000001");

        List<ChannelContract> contracts = List.of(
                // 费率用基点：60 = 千六
                ChannelContract.of(ChannelCode.WECHAT_PAY, "1900000109", 60,
                        Set.of(PaymentMethod.WECHAT_JSAPI, PaymentMethod.WECHAT_MINI,
                                PaymentMethod.WECHAT_APP, PaymentMethod.WECHAT_H5,
                                PaymentMethod.WECHAT_NATIVE, PaymentMethod.WECHAT_MICRO)),
                ChannelContract.of(ChannelCode.ALIPAY, "2021004100000000", 55,
                        Set.of(PaymentMethod.ALIPAY_WAP, PaymentMethod.ALIPAY_PAGE,
                                PaymentMethod.ALIPAY_APP, PaymentMethod.ALIPAY_F2F)),
                ChannelContract.of(ChannelCode.JD_PAY, "JD_MCH_0001", 70,
                        Set.of(PaymentMethod.JD_APP, PaymentMethod.JD_H5, PaymentMethod.JD_QR)));

        MerchantApp app = new MerchantApp(appId, "国内商城", "https://merchant.example.com/pay/notify",
                "key-cn-001", contracts);

        Merchant merchant = new Merchant(MerchantId.of("MCH00000000000001"), "示例国内商户",
                MerchantStatus.ACTIVE, List.of(app));

        save(merchant);
    }

    private void seedGlobalMerchant() {
        MerchantAppId appId = MerchantAppId.of("APP00000000000002");

        List<ChannelContract> contracts = List.of(
                // Stripe 费率 2.9% + 0.3 美元，此处简化为 290 基点
                ChannelContract.of(ChannelCode.STRIPE, "acct_1PExampleGlobal", 290,
                        Set.of(PaymentMethod.CARD, PaymentMethod.APPLE_PAY,
                                PaymentMethod.GOOGLE_PAY, PaymentMethod.SEPA_DEBIT)),
                ChannelContract.of(ChannelCode.PAYPAL, "PAYPAL_MCH_0002", 320,
                        Set.of(PaymentMethod.PAYPAL_WALLET, PaymentMethod.PAYPAL_VAULT, PaymentMethod.CARD)),
                ChannelContract.of(ChannelCode.ANTOM, "ANTOM_MCH_0002", 280,
                        Set.of(PaymentMethod.CARD, PaymentMethod.APPLE_PAY,
                                PaymentMethod.GOOGLE_PAY, PaymentMethod.ALIPAY_WAP)),
                ChannelContract.of(ChannelCode.WORLDPAY, "WP_MCH_0002", 250,
                        Set.of(PaymentMethod.CARD, PaymentMethod.APPLE_PAY, PaymentMethod.GOOGLE_PAY)));

        MerchantApp app = new MerchantApp(appId, "跨境商城", "https://merchant.example.com/global/notify",
                "key-global-001", contracts);

        Merchant merchant = new Merchant(MerchantId.of("MCH00000000000002"), "示例跨境商户",
                MerchantStatus.ACTIVE, List.of(app));

        save(merchant);
    }
}
