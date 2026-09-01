package com.study.monolithic_architecture.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 주문 처리의 환경 설정.
 *
 * <p>여기 있는 것은 <b>운영이 실제로 조정하는 값</b>뿐이다.
 * 정책 숫자는 각 서비스의 상수로 둔다. 바꾸려면 설계 판단이 필요한 값이기 때문이다.
 *
 * <p>스케줄러 주기(order.reconciliation-interval)는 {@code @Scheduled}가 직접 읽으므로
 * 여기에 담지 않는다.
 *
 * @param paymentTimeout 결제 대행 응답을 기다리는 상한. (NFR-05)
 *                       외부 시스템의 상태와 환경에 따라 실제로 달라지는 값이며,
 *                       {@code ReconciliationService.SETTLEMENT_DEADLINE}보다 짧아야 한다.
 */
@ConfigurationProperties(prefix = "order")
public record ProcessingProperties(Duration paymentTimeout) {
}
