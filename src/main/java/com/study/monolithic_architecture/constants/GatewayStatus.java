package com.study.monolithic_architecture.constants;

/**
 * 결제 대행이 돌려주는 사실. 우리 도메인의 PaymentStatus와 구분한다.
 */
public enum GatewayStatus {

	APPROVED,
	DECLINED,

	/** 승인 이력이 없다. 취소할 것이 없으므로 보상은 성공으로 판정한다. */
	NOT_FOUND,

	/** 이미 취소돼 있다. 이 역시 성공이다. */
	CANCELLED
}
