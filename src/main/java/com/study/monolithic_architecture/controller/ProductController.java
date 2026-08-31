package com.study.monolithic_architecture.controller;

import lombok.RequiredArgsConstructor;

import com.study.monolithic_architecture.controller.dto.ProductResponse;
import com.study.monolithic_architecture.service.ProductService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 상품 조회. (FR-01, FR-02)
 *
 * <p>주문 경로에 의존하지 않는다. 주문 처리가 멈춰도 이 엔드포인트는 계속 응답해야 한다. (NFR-04)
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

	private final ProductService productService;

	/** FR-01: 이름·가격·재고 수량이 함께 반환된다. */
	@GetMapping
	public List<ProductResponse> findAll() {
		return productService.findAll().stream()
			.map(ProductResponse::from)
			.toList();
	}

	/** FR-02: 없는 상품이면 404. */
	@GetMapping("/{productId}")
	public ProductResponse findOne(@PathVariable Long productId) {
		return ProductResponse.from(productService.findById(productId));
	}
}
