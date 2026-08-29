package com.demo.payment.application.command;

public record NotifyHandleResult(
        boolean accepted,
        boolean stateChanged,
        boolean duplicate,
        String notifyId,
        String message
) {
    public static NotifyHandleResult success(String notifyId, boolean changed) {
        return new NotifyHandleResult(true, changed, false, notifyId, "OK");
    }

    /** 重复通知：幂等放过，但仍需返回成功给通道，否则会一直重投 */
    public static NotifyHandleResult duplicate(String notifyId) {
        return new NotifyHandleResult(true, false, true, notifyId, "DUPLICATE_NOTIFY");
    }
}
