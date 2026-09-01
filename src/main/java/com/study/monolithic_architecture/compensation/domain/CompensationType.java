package com.study.monolithic_architecture.compensation.domain;

/**
 * 되돌려야 할 부수효과의 종류.
 */
public enum CompensationType {

	/** 붙잡아 둔 재고를 놓아준다. */
	RELEASE_STOCK,

	/** 승인된 결제를 되돌린다. 조회로 승인이 확인된 경우에만 수행한다. */
	CANCEL_PAYMENT
}
