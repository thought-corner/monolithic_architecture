package com.study.monolithic_architecture.payment.domain;

/**
 * 결제 상태.
 */
public enum PaymentStatus {

	/**
	 * 결과 미확인. 요청은 보냈으나 결과를 받지 못했다. 승인됐을 수도, 아닐 수도 있다.
	 * 종결 상태가 아니며 조회로 반드시 해소한다. (NFR-05)
	 */
	UNKNOWN,

	/** 승인됨. */
	APPROVED,

	/** 거절됨. 승인된 적이 없다. (BR-6) */
	DECLINED,

	/** 취소됨. 승인된 것을 되돌렸다. (FR-07) */
	CANCELLED;

	/** UNKNOWN만 미종결이다. 조회로 반드시 해소된다. */
	public boolean isTerminal() {
		return this != UNKNOWN;
	}
}
