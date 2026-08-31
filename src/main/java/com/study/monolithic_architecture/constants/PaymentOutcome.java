package com.study.monolithic_architecture.constants;

/**
 * 결제 시도의 결과. 주문 처리 흐름이 분기하는 기준이다.
 */
public enum PaymentOutcome {

	/** 승인됐다. 주문을 확정한다. */
	APPROVED,

	/** 거절됐다. 보상 후 PAYMENT_DECLINED로 실패시킨다. (BR-6) */
	DECLINED,

	/** 시간 예산 안에 응답이 없었다. 조회로 사실을 확인한 뒤 보상한다. (NFR-05) */
	TIMEOUT
}
