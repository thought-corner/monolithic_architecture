package com.study.monolithic_architecture.payment.gateway;

import com.study.monolithic_architecture.payment.domain.GatewayStatus;

/**
 * 결제 대행. 경계 밖의 외부 시스템이며 도메인이 아니다.
 *
 * <p>모든 호출은 멱등키를 받는다. 재시도가 이중 승인이나 이중 취소를 만들지 않게 하기 위해서다.
 * 멱등키는 주문번호에서 결정적으로 파생하며, 재시도 사이에 절대 바뀌지 않는다.
 */
public interface PaymentGateway {

	/** 승인을 요청한다. 응답이 늦으면 호출부가 시간 예산으로 끊는다. */
	GatewayStatus approve(String idempotencyKey, long amount);

	/**
	 * 실제 결과가 무엇인지 되묻는다. 타임아웃 이후의 첫 조치다.
	 * 취소를 먼저 보내지 않는 이유는, 승인된 적 없는 건을 취소하면
	 * 진짜 승인된 건을 놓치기 때문이다.
	 */
	GatewayStatus inquire(String idempotencyKey);

	/** 승인을 되돌린다. 이미 취소됐거나 승인 이력이 없으면 그대로 성공이다. */
	GatewayStatus cancel(String idempotencyKey);
}
