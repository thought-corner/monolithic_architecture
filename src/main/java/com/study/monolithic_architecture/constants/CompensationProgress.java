package com.study.monolithic_architecture.constants;

/**
 * 보상 작업의 진행 상태. 주문 상태(OrderStatus)와 다른 축이다.
 */
public enum CompensationProgress {

	/** 아직 성공하지 못했다. 미결 보상. */
	PENDING,

	/** 되돌리기가 끝났다. */
	DONE,

	/** 최대 시도를 소진했다. 운영 개입 대상. */
	EXHAUSTED
}
