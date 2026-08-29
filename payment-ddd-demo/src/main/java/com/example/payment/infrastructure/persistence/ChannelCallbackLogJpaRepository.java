package com.example.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 回调留痕 Spring Data 仓储接口。
 */
public interface ChannelCallbackLogJpaRepository extends JpaRepository<ChannelCallbackLogPO, Long> {

    Optional<ChannelCallbackLogPO> findByLogId(String logId);
}
