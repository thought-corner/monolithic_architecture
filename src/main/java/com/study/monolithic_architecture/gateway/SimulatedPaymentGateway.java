package com.study.monolithic_architecture.gateway;

import com.study.monolithic_architecture.constants.GatewayStatus;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 결제 대행 모사. 실제 연동 없이 규칙으로만 응답한다.
 *
 * <p>금액이 기준액 이상이면 거절한다. (BR-6) 실패 경로를 언제든 재현하기 위한 규칙이다.
 * 멱등키별 승인 결과를 기억하므로 같은 키로 다시 부르면 새 승인이 생기지 않는다.
 *
 * <p>응답 지연이나 무응답을 재현해야 한다면 설정을 두지 않고 이 클래스 대신
 * 다른 {@link PaymentGateway} 구현을 끼운다. 운영이 쓰지 않는 값을 설정으로 만들지 않기 위해서다.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

	/**
	 * BR-6: 이 금액 이상이면 거절한다. 실패 경로를 언제든 재현하기 위한 규칙이다.
	 *
	 * <p>업무 규칙이므로 설정이 아니라 상수다. 환경에 따라 달라지는 값이 아니며,
	 * 바뀐다면 그것은 배포 설정 변경이 아니라 요구사항 변경이다.
	 */
	private static final long DECLINE_THRESHOLD = 100_000L;

	/** 멱등키 → 마지막으로 확정된 결과. 실제 PG의 거래 원장을 대신한다. */
	private final Map<String, GatewayStatus> ledger = new ConcurrentHashMap<>();

	@Override
	public GatewayStatus approve(String idempotencyKey, long amount) {
		// 같은 키로 다시 부르면 원래 결과를 그대로 돌려준다. 이중 승인이 생기지 않는다.
		return ledger.computeIfAbsent(idempotencyKey, key ->
			amount >= DECLINE_THRESHOLD ? GatewayStatus.DECLINED : GatewayStatus.APPROVED);
	}

	@Override
	public GatewayStatus inquire(String idempotencyKey) {
		return ledger.getOrDefault(idempotencyKey, GatewayStatus.NOT_FOUND);
	}

	@Override
	public GatewayStatus cancel(String idempotencyKey) {
		GatewayStatus current = ledger.get(idempotencyKey);
		if (current == null) {
			return GatewayStatus.NOT_FOUND;
		}
		if (current == GatewayStatus.APPROVED) {
			ledger.put(idempotencyKey, GatewayStatus.CANCELLED);
		}
		// 이미 CANCELLED거나 DECLINED면 되돌릴 것이 없다. 둘 다 성공이다.
		return GatewayStatus.CANCELLED;
	}
}
