package com.study.monolithic_architecture.product.domain;

import com.study.monolithic_architecture.order.domain.FailureReason;

/**
 * 가용재고가 요청 수량보다 적다. (BR-3)
 * 서비스 계층은 이 예외를 FailureReason.OUT_OF_STOCK으로 옮긴다. (FR-05)
 */
public class InsufficientStockException extends RuntimeException {

	public InsufficientStockException(Long productId, int requested, int available) {
		super("가용재고가 부족하다: 상품 %d, 요청 %d, 가용 %d".formatted(productId, requested, available));
	}
}
