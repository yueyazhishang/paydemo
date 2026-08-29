package com.zxpay.domain.merchant.model;

import com.zxpay.sharedkernel.exception.DomainException;
import com.zxpay.sharedkernel.model.AggregateRoot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 商户聚合根。
 *
 * <p>一致性边界划在这里的理由：商户与其下的应用、签约关系，
 * 需要在「商户被暂停时，其下所有应用立即不可用」这条规则上保持强一致。
 * 如果把应用拆成独立聚合，就只能通过领域事件最终一致地处理，
 * 会出现「商户已暂停但某应用还能收款」的窗口期，风险不可接受。
 *
 * <p>反过来说，<b>签约关系没有独立成聚合</b>：它不会脱离商户独立变更，
 * 生命周期完全依附于应用，因此作为实体内嵌即可。
 * 这是聚合设计里最常被问到的问题——不是「什么都要拆」，
 * 而是「按不变量划边界，不变量之外的才拆」。
 */
public final class Merchant extends AggregateRoot<MerchantId> {

    private final MerchantId merchantId;
    private final String name;
    private MerchantStatus status;
    private final List<MerchantApp> apps;

    public Merchant(MerchantId merchantId, String name, MerchantStatus status, List<MerchantApp> apps) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId must not be null");
        }
        this.merchantId = merchantId;
        this.name = name;
        this.status = status == null ? MerchantStatus.ACTIVE : status;
        this.apps = apps == null ? new ArrayList<>() : new ArrayList<>(apps);
    }

    @Override
    public MerchantId id() {
        return merchantId;
    }

    public String name() {
        return name;
    }

    public MerchantStatus status() {
        return status;
    }

    public List<MerchantApp> apps() {
        return Collections.unmodifiableList(apps);
    }

    // ---------- 领域行为 ----------

    /**
     * 校验商户是否可以发起新交易，不通过则抛领域异常。
     *
     * <p>把这条规则放在聚合上而不是应用层的 if 判断里，
     * 是为了保证<b>无论从哪个入口进来都绕不过去</b>：
     * REST 下单、定时任务重试、内部管理台补单，全部走同一个守卫。
     */
    public void requireAcceptableForNewPayment() {
        if (!status.canAcceptNewPayment()) {
            throw new DomainException("MERCHANT_NOT_OPERABLE",
                    "merchant " + merchantId.value() + " is " + status.displayName() + ", cannot accept new payment");
        }
    }

    public Optional<MerchantApp> appOf(MerchantAppId appId) {
        return apps.stream()
                .filter(app -> app.appId().equals(appId))
                .findFirst();
    }

    /** 取应用，不存在直接抛异常。避免调用方到处写 Optional 判空。 */
    public MerchantApp requireApp(MerchantAppId appId) {
        return appOf(appId).orElseThrow(() -> new DomainException("APP_NOT_FOUND",
                "app " + appId.value() + " not found under merchant " + merchantId.value()));
    }

    public void suspend(String reason) {
        if (status == MerchantStatus.CLOSED) {
            throw new DomainException("MERCHANT_ALREADY_CLOSED",
                    "closed merchant cannot be suspended: " + merchantId.value());
        }
        this.status = MerchantStatus.SUSPENDED;
    }

    public void activate() {
        if (status == MerchantStatus.CLOSED) {
            throw new DomainException("MERCHANT_ALREADY_CLOSED",
                    "closed merchant cannot be reactivated: " + merchantId.value());
        }
        this.status = MerchantStatus.ACTIVE;
    }
}
