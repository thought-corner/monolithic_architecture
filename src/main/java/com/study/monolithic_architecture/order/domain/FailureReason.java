package com.study.monolithic_architecture.order.domain;

/**
 * 주문 실패 사유. 주문이 실패로 종결됐을 때만 값을 갖는다.
 */
public enum FailureReason {

	/** 가용재고가 요청 수량보다 적다. (FR-05, BR-3) */
	OUT_OF_STOCK,

	/** 결제 대행이 거절했다. (FR-06, BR-6) */
	PAYMENT_DECLINED,

	/** 결제 대행이 시간 예산 안에 응답하지 않았고, 보상이 끝났다. (FR-06 + NFR-05) */
	TIMEOUT
}
