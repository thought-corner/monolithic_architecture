package com.study.monolithic_architecture.constants;

/**
 * 주문 상태. BR-4가 허용하는 셋뿐이다.
 * PENDING·PROCESSING·COMPENSATING 같은 중간 상태를 추가하지 않는다.
 */
public enum OrderStatus {

	/** 접수됨. 요청이 유효해 받아들인 상태이며 아직 확정이 아니다. */
	ACCEPTED,

	/** 확정됨. 재고 차감과 결제 승인이 모두 끝난 종결 상태. */
	CONFIRMED,

	/** 실패함. 원복까지 끝난 종결 상태. */
	FAILED;

	public boolean isTerminal() {
		return this == CONFIRMED || this == FAILED;
	}
}
