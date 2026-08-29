package com.zxpay.domain.refund.port;

import com.zxpay.domain.merchant.model.MerchantAppId;
import com.zxpay.domain.payment.model.PaymentOrderId;
import com.zxpay.domain.refund.model.RefundOrder;
import com.zxpay.domain.refund.model.RefundOrderId;
import com.zxpay.domain.refund.model.RefundStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 出站端口：退款单仓储。
 */
public interface RefundOrderRepository {

    Optional<RefundOrder> findById(RefundOrderId refundId);

    /** 业务幂等查询：同一商户退款单号只能有一笔退款。 */
    Optional<RefundOrder> findByMerchantRefundNo(MerchantAppId appId, String merchantRefundNo);

    /** 该支付单下的全部退款。用于计算累计退款与退款次数。 */
    List<RefundOrder> findByPaymentOrderId(PaymentOrderId paymentOrderId);

    void save(RefundOrder refundOrder);

    /** 扫描长时间停留在非终态的退款单，供补偿任务主动查询。 */
    List<RefundOrder> findPendingBefore(List<RefundStatus> statuses, Instant before, int limit);
}
