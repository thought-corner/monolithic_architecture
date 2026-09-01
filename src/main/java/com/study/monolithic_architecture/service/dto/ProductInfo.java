package com.study.monolithic_architecture.service.dto;

import com.study.monolithic_architecture.domain.Product;

/**
 * 상품 조회 결과. 이름·가격·재고 수량. (FR-01, FR-02)
 *
 * <p>재고 수량은 총재고다. 가용재고는 요구사항에 없으므로 담지 않는다.
 */
public record ProductInfo(Long productId, String name, long price, int stockQuantity) {

	public static ProductInfo from(Product product) {
		return new ProductInfo(
			product.getId(), product.getName(), product.getPrice(), product.getStockQuantity());
	}
}
