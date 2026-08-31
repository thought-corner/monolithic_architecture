package com.study.monolithic_architecture.exception;

/** 없는 주문이다. (FR-08) */
public class OrderNotFoundException extends RuntimeException {

	public OrderNotFoundException(String orderNo) {
		super("주문을 찾을 수 없다: " + orderNo);
	}
}
