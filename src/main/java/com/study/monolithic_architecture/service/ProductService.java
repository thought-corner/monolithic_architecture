package com.study.monolithic_architecture.service;

import lombok.RequiredArgsConstructor;

import com.study.monolithic_architecture.service.dto.ProductInfo;
import com.study.monolithic_architecture.exception.ProductNotFoundException;
import com.study.monolithic_architecture.repository.ProductRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 상품 조회. (FR-01, FR-02)
 *
 * <p>주문 처리에 의존하지 않는다. 주문이 동작하지 않아도 이 경로는 살아 있어야 한다. (NFR-04)
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProductService {

	private final ProductRepository productRepository;

	/** FR-01: 이름·가격·재고 수량을 함께 돌려준다. */
	public List<ProductInfo> findAll() {
		return productRepository.findAll().stream()
			.map(ProductInfo::from)
			.toList();
	}

	/** FR-02: 없는 상품이면 예외. 표현 계층이 404로 옮긴다. */
	public ProductInfo findById(Long productId) {
		return productRepository.findById(productId)
			.map(ProductInfo::from)
			.orElseThrow(() -> new ProductNotFoundException(productId));
	}
}
