package com.payment.application.payment;

import com.payment.application.payment.dto.CreatePaymentRequest;
import com.payment.application.payment.dto.CreatePaymentResponse;
import com.payment.application.payment.dto.PaymentQueryResponse;
import com.payment.application.payment.dto.RefundRequest;
import com.payment.domain.channel.model.enums.ChannelCode;
import com.payment.domain.payment.model.aggregate.PaymentOrder;
import com.payment.domain.payment.model.entity.PaymentTransaction;
import com.payment.domain.payment.model.enums.PaymentStatus;
import com.payment.domain.payment.model.valueobject.Money;
import com.payment.domain.payment.model.valueobject.OrderId;
import com.payment.domain.payment.model.valueobject.PaymentId;
import com.payment.domain.payment.repository.PaymentOrderRepository;
import com.payment.domain.payment.service.PaymentDomainService;
import com.payment.domain.refund.model.aggregate.RefundOrder;
import com.payment.domain.refund.service.RefundDomainService;
import com.payment.infrastructure.channel.ChannelAdapterRegistry;
import com.payment.infrastructure.channel.adapter.PaymentChannelAdapter;
import com.payment.infrastructure.channel.adapter.PaymentChannelAdapter.ChannelCreateResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 支付应用服务
 * 
 * 职责:
 * 1. 协调领域对象完成支付业务流程
 * 2. 处理事务边界
 * 3. 调用基础设施服务(通道适配器)
 * 4. DTO与领域对象转换
 * 
 * 注意:
 * - 不包含业务逻辑，业务逻辑在领域层
 * - 负责编排和协调
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentAppService {
    
    private final PaymentDomainService paymentDomainService;
    private final RefundDomainService refundDomainService;
    private final PaymentOrderRepository paymentOrderRepository;
    private final ChannelAdapterRegistry channelAdapterRegistry;
    
    /**
     * 创建支付订单
     * 
     * 流程:
     * 1. 验证参数
     * 2. 创建领域对象
     * 3. 调用通道适配器创建支付
     * 4. 保存结果
     */
    @Transactional
    public CreatePaymentResponse createPayment(CreatePaymentRequest request) {
        log.info("创建支付订单, merchantId={}, orderId={}, channelCode={}", 
                request.getMerchantId(), request.getOrderId(), request.getChannelCode());
        
        // 1. 解析通道编码
        ChannelCode channelCode = ChannelCode.fromCode(request.getChannelCode());
        
        // 2. 创建领域支付订单
        PaymentOrder order = paymentDomainService.createPaymentOrder(
                request.getMerchantId(),
                request.getUserId(),
                OrderId.of(request.getOrderId()),
                Money.of(request.getAmount().toString(), request.getCurrency()),
                request.getDescription(),
                channelCode,
                request.getNotifyUrl(),
                request.getReturnUrl(),
                request.getExtra()
        );
        
        // 3. 获取对应通道适配器
        PaymentChannelAdapter adapter = channelAdapterRegistry.getAdapter(channelCode);
        
        // 4. 发起支付
        PaymentTransaction transaction = order.initiatePayment(channelCode);
        ChannelCreateResult channelResult = adapter.createPayment(order);
        
        // 5. 处理通道返回结果
        if (channelResult.isSuccess()) {
            // 保存通道返回的支付参数到交易记录
            transaction.getChannelPaymentParams().putAll(channelResult.getPaymentParams());
        } else {
            order.processPaymentFailure(channelResult.getErrorMessage(), transaction);
        }
        
        // 6. 保存订单
        paymentOrderRepository.save(order);
        
        // 7. 构建响应
        return buildCreatePaymentResponse(order, channelResult);
    }
    
    /**
     * 查询支付订单
     */
    public PaymentQueryResponse queryPayment(String paymentId) {
        PaymentOrder order = paymentOrderRepository.findById(PaymentId.of(paymentId))
                .orElseThrow(() -> new IllegalArgumentException("支付订单不存在: " + paymentId));
        
        return buildPaymentQueryResponse(order);
    }
    
    /**
     * 查询支付状态(从通道同步最新状态)
     */
    @Transactional
    public PaymentQueryResponse syncPaymentStatus(String paymentId) {
        PaymentOrder order = paymentOrderRepository.findById(PaymentId.of(paymentId))
                .orElseThrow(() -> new IllegalArgumentException("支付订单不存在: " + paymentId));
        
        // 获取当前交易
        PaymentTransaction transaction = order.getCurrentTransaction().orElse(null);
        if (transaction == null || transaction.getChannelOrderId() == null) {
            return buildPaymentQueryResponse(order);
        }
        
        // 从通道查询最新状态
        PaymentChannelAdapter adapter = channelAdapterRegistry.getAdapter(order.getChannelCode());
        PaymentChannelAdapter.ChannelQueryResult queryResult = adapter.queryPayment(transaction.getChannelOrderId());
        
        // 根据查询结果更新订单状态
        if (queryResult.isPaid() && order.getStatus() == PaymentStatus.PENDING) {
            order.processPaymentSuccess(transaction.getChannelOrderId(), transaction);
            paymentOrderRepository.save(order);
        }
        
        return buildPaymentQueryResponse(order);
    }
    
    /**
     * 关闭支付订单
     */
    @Transactional
    public boolean closePayment(String paymentId) {
        PaymentOrder order = paymentOrderRepository.findById(PaymentId.of(paymentId))
                .orElseThrow(() -> new IllegalArgumentException("支付订单不存在: " + paymentId));
        
        if (!order.getStatus().canClose()) {
            throw new IllegalStateException("当前状态不允许关闭: " + order.getStatus());
        }
        
        // 调用通道关闭
        PaymentChannelAdapter adapter = channelAdapterRegistry.getAdapter(order.getChannelCode());
        PaymentTransaction transaction = order.getCurrentTransaction().orElse(null);
        if (transaction != null && transaction.getChannelOrderId() != null) {
            adapter.closePayment(transaction.getChannelOrderId());
        }
        
        order.close("用户关闭");
        paymentOrderRepository.save(order);
        
        return true;
    }
    
    /**
     * 发起退款
     */
    @Transactional
    public RefundRequest createRefund(RefundRequest request) {
        PaymentOrder order = paymentOrderRepository.findById(PaymentId.of(request.getPaymentId()))
                .orElseThrow(() -> new IllegalArgumentException("支付订单不存在: " + request.getPaymentId()));
        
        // 验证是否可以退款
        if (!order.getStatus().canRefund()) {
            throw new IllegalStateException("支付订单状态不允许退款: " + order.getStatus());
        }
        
        // 验证退款金额
        Money refundAmount = Money.of(request.getRefundAmount().toString(), order.getAmount().getCurrency().getCurrencyCode());
        Money refundableAmount = order.getRefundableAmount();
        if (refundAmount.isGreaterThan(refundableAmount)) {
            throw new IllegalArgumentException("退款金额超过可退款金额");
        }
        
        // 创建退款订单
        RefundOrder refundOrder = refundDomainService.createRefundOrder(
                order, refundAmount, request.getReason(), request.getNotifyUrl()
        );
        
        // 调用通道退款
        PaymentChannelAdapter adapter = channelAdapterRegistry.getAdapter(order.getChannelCode());
        PaymentChannelAdapter.ChannelRefundResult refundResult = adapter.refund(refundOrder);
        
        // 根据退款结果更新状态
        if (refundResult.isSuccess()) {
            refundOrder.markSuccess(refundResult.getChannelRefundId());
        } else {
            refundOrder.markFailed(refundResult.getErrorMessage());
        }
        
        return convertToRefundRequest(refundOrder);
    }
    
    // ========== 私有方法 ==========
    
    private CreatePaymentResponse buildCreatePaymentResponse(PaymentOrder order, ChannelCreateResult channelResult) {
        Map<String, String> paymentParams = channelResult.isSuccess() ? 
                channelResult.getPaymentParams() : null;
        
        return CreatePaymentResponse.builder()
                .paymentId(order.getPaymentId().getValue())
                .orderId(order.getMerchantOrderId().getValue())
                .amount(order.getAmount().getAmount().toString())
                .currency(order.getAmount().getCurrency().getCurrencyCode())
                .status(order.getStatus().name())
                .statusDesc(order.getStatus().getDisplayName())
                .channelCode(order.getChannelCode().getCode())
                .channelName(order.getChannelCode().getDisplayName())
                .paymentParams(paymentParams)
                .expireTime(order.getExpireTime())
                .createdAt(order.getCreatedAt())
                .build();
    }
    
    private PaymentQueryResponse buildPaymentQueryResponse(PaymentOrder order) {
        String channelOrderId = null;
        if (order.getCurrentTransaction().isPresent()) {
            channelOrderId = order.getCurrentTransaction().get().getChannelOrderId();
        }
        
        return PaymentQueryResponse.builder()
                .paymentId(order.getPaymentId().getValue())
                .orderId(order.getMerchantOrderId().getValue())
                .merchantId(order.getMerchantId())
                .amount(order.getAmount().getAmount().toString())
                .currency(order.getAmount().getCurrency().getCurrencyCode())
                .status(order.getStatus().name())
                .statusDesc(order.getStatus().getDisplayName())
                .channelCode(order.getChannelCode().getCode())
                .channelName(order.getChannelCode().getDisplayName())
                .channelOrderId(channelOrderId)
                .description(order.getDescription())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .successAt(order.getSuccessAt())
                .build();
    }
    
    private RefundRequest convertToRefundRequest(RefundOrder refundOrder) {
        RefundRequest response = new RefundRequest();
        response.setRefundId(refundOrder.getRefundId().getValue());
        response.setPaymentId(refundOrder.getPaymentId().getValue());
        response.setRefundAmount(refundOrder.getRefundAmount().getAmount());
        response.setStatus(refundOrder.getStatus().name());
        response.setStatusDesc(refundOrder.getStatus().getDisplayName());
        response.setReason(refundOrder.getReason());
        return response;
    }
}
