package com.study.monolithic_architecture.product.domain;

/** 없는 상품이다. FR-02는 404로, FR-04는 접수 거절로 옮긴다. */
public class ProductNotFoundException extends RuntimeException {

	public ProductNotFoundException(Long productId) {
		super("상품을 찾을 수 없다: " + productId);
	}
}
