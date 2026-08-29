package com.example.payment.infrastructure.persistence;

import com.example.payment.domain.payment.model.ChannelCallbackLog;
import com.example.payment.domain.payment.repository.ChannelCallbackLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 回调留痕仓储端口实现。
 */
@Repository
@RequiredArgsConstructor
public class ChannelCallbackLogRepositoryImpl implements ChannelCallbackLogRepository {

    private final ChannelCallbackLogJpaRepository jpaRepository;

    @Override
    public ChannelCallbackLog save(ChannelCallbackLog log) {
        ChannelCallbackLogPO po = new ChannelCallbackLogPO();
        po.setLogId(log.getLogId());
        po.setChannel(log.getChannel());
        po.setOurTradeNo(log.getOurTradeNo());
        po.setCallbackType(log.getCallbackType());
        po.setSignVerified(log.isSignVerified());
        po.setTradeSuccess(log.isTradeSuccess());
        po.setResult(log.getResult());
        po.setErrorMessage(log.getErrorMessage());
        po.setRawBody(log.getRawBody());
        po.setReceivedAt(log.getReceivedAt());
        jpaRepository.save(po);
        return log;
    }
}
