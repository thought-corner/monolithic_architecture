package com.study.monolithic_architecture.product.controller.dto;

import com.study.monolithic_architecture.product.service.dto.ProductInfo;

/**
 * 상품 조회 응답. 이름·가격·재고 수량이 함께 반환된다. (FR-01, FR-02)
 */
public record ProductResponse(Long productId, String name, long price, int stockQuantity) {

	public static ProductResponse from(ProductInfo info) {
		return new ProductResponse(
			info.productId(), info.name(), info.price(), info.stockQuantity());
	}
}
