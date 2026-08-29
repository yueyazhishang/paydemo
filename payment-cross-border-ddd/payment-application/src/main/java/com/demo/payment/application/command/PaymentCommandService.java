package com.demo.payment.application.command;

import com.demo.payment.application.idempotency.IdempotencyGuard;
import com.demo.payment.application.outbox.OutboxService;
import com.demo.payment.domain.acquiring.model.aggregate.PaymentOrder;
import com.demo.payment.domain.acquiring.model.vo.OutTradeNo;
import com.demo.payment.domain.channel.model.ChannelCode;
import com.demo.payment.domain.channel.model.ChannelResultStatus;
import com.demo.payment.domain.channel.model.PaymentMethodType;
import com.demo.payment.domain.channel.route.ChannelRouter;
import com.demo.payment.domain.channel.spi.*;
import com.demo.payment.domain.acquiring.repository.PaymentOrderRepository;
import com.demo.payment.shared.money.Money;
import com.demo.payment.shared.util.IdGenerator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;

/**
 * 支付命令服务 —— 应用层的编排核心。
 *
 * <h3>应用层的职责边界</h3>
 * <p>应用层<b>不做业务判断</b>（那是领域层的事），它负责：
 * <ol>
 *   <li><b>事务边界</b>：一次用例一个事务</li>
 *   <li><b>编排</b>：聚合 + 仓储 + 外部端口的调用顺序</li>
 *   <li><b>技术关注点</b>：幂等、锁、重试、事件发布</li>
 * </ol>
 *
 * <h3>支付主流程（重点）</h3>
 * <pre>
 *   1. 幂等检查（接入层）
 *   2. 业务幂等：按 (merchantId, merchantOrderNo) 查重
 *   3. 路由：选出候选通道列表
 *   4. 逐个尝试通道：
 *      4a. 生成 attemptSeq 对应的 outTradeNo（每次尝试唯一！）
 *      4b. 调用通道
 *      4c. SUCCEEDED → 更新订单 → 结束
 *          PENDING   → 保存凭证 → 结束（等回调/查证）
 *          UNKNOWN   → 保存 + 登记查证任务 → 结束（绝不关单！）
 *          FAILED    → 记录失败 → 尝试下一个通道
 *   5. 全部失败 → 标记订单失败
 *   6. 保存聚合 + 捕获领域事件进 Outbox
 * </pre>
 *
 * <p><b>第 4a 步是最容易出错的地方：</b>
 * 重试时若复用同一个 outTradeNo，微信/支付宝会返回"订单已存在"，
 * 重试永远失败；若每次都换新号，则必须确保旧号已失效（否则可能重复扣款）。
 * 本实现采用"每尝试一号"策略，并在 UNKNOWN 时靠查证兜底。
 */
public class PaymentCommandService {

    private final PaymentOrderRepository repository;
    private final ChannelRouter router;
    private final Map<ChannelCode, PaymentChannelPort> channels;
    private final IdempotencyGuard idempotencyGuard;
    private final OutboxService outboxService;

    public PaymentCommandService(PaymentOrderRepository repository,
                                 ChannelRouter router,
                                 Map<ChannelCode, PaymentChannelPort> channels,
                                 IdempotencyGuard idempotencyGuard,
                                 OutboxService outboxService) {
        this.repository = repository;
        this.router = router;
        this.channels = channels;
        this.idempotencyGuard = idempotencyGuard;
        this.outboxService = outboxService;
    }

    /**
     * 创建并发起支付。
     *
     * <p><b>事务边界说明：</b>
     * 本方法在一个事务内完成"订单落库 + Outbox 写入"。
     * <b>通道调用必须在事务之外</b> —— 否则网络超时会导致事务长时间挂起，
     * 占用数据库连接，高并发下直接压垮 DB。
     * 正确做法：先落库（事务内），再调通道（事务外），再更新状态（新事务）。
     */
    public PayResult pay(CreatePaymentCommand cmd) {
        // ---- 第一层幂等：接入层 ----
        String fingerprint = IdempotencyGuard.fingerprint(
                cmd.merchantId(), cmd.merchantOrderNo(),
                String.valueOf(cmd.amount().minorUnits()),
                cmd.amount().currency().code(),
                cmd.paymentMethod().name());

        return idempotencyGuard.execute(
                cmd.idempotencyKey(),
                fingerprint,
                () -> doPay(cmd),
                r -> r.toString(),
                s -> PayResult.parse(s)
        );
    }

    private PayResult doPay(CreatePaymentCommand cmd) {
        // ---- 第二层幂等：业务层（商户订单号唯一性）----
        var existing = repository.findByMerchantOrderNo(cmd.merchantId(), cmd.merchantOrderNo());
        if (existing.isPresent()) {
            PaymentOrder order = existing.get();
            // 已存在则直接返回原单，绝不重复创建 —— 这是防重复下单的最后兜底
            return PayResult.of(order, "EXISTING_ORDER_RETURNED");
        }

        // ---- 1. 创建聚合 ----
        PaymentOrder order = PaymentOrder.create(
                cmd.merchantId(), cmd.merchantOrderNo(), cmd.amount(),
                cmd.paymentMethod(), cmd.subject(), cmd.notifyUrl(), cmd.expireAt());

        // ---- 2. 路由 ----
        RoutingContext routingCtx = new RoutingContext(
                cmd.merchantId(), cmd.paymentMethod(), cmd.amount(),
                cmd.amount().currency(), cmd.countryCode(), cmd.clientIp(), cmd.scene());

        List<ChannelCode> candidates = router.route(routingCtx);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("无可用通道：支付方式=" + cmd.paymentMethod()
                    + " 币种=" + cmd.amount().currency().code());
        }

        // ---- 3. 落库（事务内）----
        repository.save(order);

        // ---- 4. 逐个尝试通道（事务外）----
        Lock lock = repository.obtainLock(order.id());
        lock.lock();
        try {
            for (int i = 0; i < candidates.size(); i++) {
                ChannelCode channelCode = candidates.get(i);
                PaymentChannelPort channel = channels.get(channelCode);
                if (channel == null) {
                    continue;
                }

                int attemptSeq = i + 1;
                OutTradeNo outTradeNo = OutTradeNo.of(
                        IdGenerator.outTradeNo(order.id().value(), attemptSeq));

                // 登记尝试（生成 attempt 实体）
                order.startAttempt(channelCode, outTradeNo);

                PayCommand payCommand = PayCommand.builder()
                        .outTradeNo(outTradeNo)
                        .amount(cmd.amount())
                        .paymentMethod(cmd.paymentMethod())
                        .subject(cmd.subject())
                        .notifyUrl(cmd.notifyUrl())
                        .returnUrl(cmd.returnUrl())
                        .clientIp(cmd.clientIp())
                        .payerId(cmd.payerId())
                        .paymentCredential(cmd.paymentCredential())
                        .idempotencyKey(cmd.idempotencyKey())
                        .countryCode(cmd.countryCode())
                        .build();

                PayResponse response;
                try {
                    response = channel.pay(payCommand);
                } catch (Exception e) {
                    // 通道异常不算订单失败，继续尝试下一个通道
                    continue;
                }

                if (response.isSucceeded()) {
                    order.applyChannelResult(outTradeNo, true, cmd.amount(),
                            response.channelTransactionId(), "SUCCESS", null);
                    break;
                } else if (response.isUnknown()) {
                    // UNKNOWN：保持"支付中"，由查证补偿任务兜底。
                    // 绝不在这里关单或判失败 —— 那会造成掉单/资损。
                    break;
                } else if (response.isPending()) {
                    // 已拿到支付凭证，等待用户付款或回调
                    break;
                }
                // FAILED：继续尝试下一个通道
            }

            if (order.status().isProcessing() && !order.attempts().isEmpty()
                    && order.attempts().stream().allMatch(a -> a.status() == com.demo.payment.domain.acquiring.model.entity.PaymentAttempt.AttemptStatus.FAILED)) {
                order.markFailed("所有通道尝试均失败");
            }

            // ---- 5. 保存 + 捕获事件 ----
            repository.save(order);
            outboxService.capture("PaymentOrder", order.id().value(), order.pullDomainEvents());

            return PayResult.of(order, "SUBMITTED");
        } finally {
            lock.unlock();
        }
    }

    /**
     * 主动查证 —— 补偿 UNKNOWN 与丢失回调的关键手段。
     *
     * <p>调用时机：
     * <ul>
     *   <li>下单返回 UNKNOWN 后立即触发</li>
     *   <li>定时任务扫描"支付中"超过 N 分钟的订单</li>
     *   <li>收到回调时，先查证再更新（不信任回调内容）</li>
     * </ul>
     */
    public boolean reconcile(PaymentOrder order) {
        var attempt = order.currentAttempt();
        if (attempt == null) {
            return false;
        }
        PaymentChannelPort channel = channels.get(attempt.channelCode());
        if (channel == null) {
            return false;
        }

        Lock lock = repository.obtainLock(order.id());
        lock.lock();
        try {
            QueryResponse resp = channel.query(QueryCommand.byOutTradeNo(attempt.outTradeNo()));
            if (resp.status() != null && resp.status().isFinal()) {
                boolean success = resp.status() == ChannelResultStatus.SUCCEEDED;
                boolean changed = order.applyChannelResult(attempt.outTradeNo(), success,
                        resp.amount(), resp.channelTransactionId(), resp.channelRawStatus(), null);
                if (changed) {
                    repository.save(order);
                    outboxService.capture("PaymentOrder", order.id().value(), order.pullDomainEvents());
                }
                return changed;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
}
