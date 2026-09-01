package com.study.monolithic_architecture.product.service;

import com.study.monolithic_architecture.order.service.StockReservationService;
import com.study.monolithic_architecture.product.domain.Product;
import com.study.monolithic_architecture.product.domain.ProductNotFoundException;
import com.study.monolithic_architecture.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 해제·차감. 각각이 독립된 트랜잭션이다.
 *
 * <p>확보는 여기 없다. 확보와 '되돌릴 의무'는 한 트랜잭션에 함께 커밋돼야 하므로
 * {@link StockReservationService}가 맡는다. 확보만 커밋되고 처리가 끊기면
 * 붙잡힌 재고를 아무도 풀지 못한다.
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private final ProductRepository productRepository;

    /** 붙잡아 둔 수량을 놓아준다. 실패한 주문의 재고 원복이다. (FR-07) */
    @Transactional
    public void release(Long productId, int quantity) {
        product(productId).release(quantity);
    }

    /** 총재고를 실제로 줄인다. 주문 확정에서 단 한 번만 일어난다. */
    @Transactional
    public void deduct(Long productId, int quantity) {
        product(productId).deduct(quantity);
    }

    private Product product(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }
}
