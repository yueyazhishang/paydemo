package com.demo.payment.domain;

import com.demo.payment.domain.acquiring.statemachine.PaymentStateMachine;
import com.demo.payment.domain.acquiring.statemachine.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 状态机测试 —— 状态机是支付系统防资损的最后一道闸门。
 */
class PaymentStateMachineTest {

    @Test
    @DisplayName("合法路径：CREATED → PAYING → PAID → PARTIALLY_REFUNDED → REFUNDED")
    void legalPath() {
        assertDoesNotThrow(() -> {
            PaymentStateMachine.validate(PaymentStatus.CREATED, PaymentStatus.PAYING);
            PaymentStateMachine.validate(PaymentStatus.PAYING, PaymentStatus.PAID);
            PaymentStateMachine.validate(PaymentStatus.PAID, PaymentStatus.PARTIALLY_REFUNDED);
            PaymentStateMachine.validate(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED);
        });
    }

    @Test
    @DisplayName("终态不可变：REFUNDED 不能变回 PAID")
    void terminalStateImmutable() {
        assertThrows(IllegalStateException.class,
                () -> PaymentStateMachine.validate(PaymentStatus.REFUNDED, PaymentStatus.PAID));
        assertThrows(IllegalStateException.class,
                () -> PaymentStateMachine.validate(PaymentStatus.CLOSED, PaymentStatus.PAYING));
        assertThrows(IllegalStateException.class,
                () -> PaymentStateMachine.validate(PaymentStatus.FAILED, PaymentStatus.PAID));
    }

    @Test
    @DisplayName("不存在状态回退：PAID 不能回到 PAYING")
    void noBackwardTransition() {
        assertThrows(IllegalStateException.class,
                () -> PaymentStateMachine.validate(PaymentStatus.PAID, PaymentStatus.PAYING),
                "支付成功后不能回退到支付中");
    }

    @Test
    @DisplayName("未支付不能退款")
    void cannotRefundBeforePaid() {
        assertThrows(IllegalStateException.class,
                () -> PaymentStateMachine.validate(PaymentStatus.CREATED, PaymentStatus.REFUNDED));
    }

    @Test
    @DisplayName("相同状态是幂等的，不视为非法转换")
    void sameStateIsIdempotent() {
        assertDoesNotThrow(() ->
                PaymentStateMachine.validate(PaymentStatus.PAID, PaymentStatus.PAID),
                "重复回调到达相同状态应被放过，这是幂等的基础");
    }

    @Test
    @DisplayName("两段式路径：PAYING → AUTHORIZED → CAPTURING → PAID")
    void twoPhasePath() {
        assertDoesNotThrow(() -> {
            PaymentStateMachine.validate(PaymentStatus.PAYING, PaymentStatus.AUTHORIZED);
            PaymentStateMachine.validate(PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURING);
            PaymentStateMachine.validate(PaymentStatus.CAPTURING, PaymentStatus.PAID);
        });
    }
}
