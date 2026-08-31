package com.study.monolithic_architecture.service;

import com.study.monolithic_architecture.constants.CompensationType;
import com.study.monolithic_architecture.domain.CompensationTask;
import com.study.monolithic_architecture.domain.Product;
import com.study.monolithic_architecture.exception.ProductNotFoundException;
import com.study.monolithic_architecture.repository.CompensationTaskRepository;
import com.study.monolithic_architecture.repository.ProductRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문을 위해 재고를 확보한다. (FR-05)
 *
 * <p><b>확보와 "되돌릴 의무"를 한 트랜잭션에 함께 커밋한다.</b> 확보만 커밋해 두고
 * 되돌릴 일을 나중에 등록하면, 그 사이에 처리가 끊겼을 때 붙잡힌 재고를 아무도
 * 되돌리지 못한다. 총재고와 확정 수량 합은 그대로라 BR-7 검사는 통과하지만
 * 가용재고가 조용히 줄어든다.
 *
 * <p>확보에 실패하면 트랜잭션이 통째로 롤백되므로 헛된 보상 작업도 남지 않는다.
 */
@Service
@RequiredArgsConstructor
public class StockReservationService {

    private final ProductRepository productRepository;
    private final CompensationTaskRepository taskRepository;
    private final TransactionTemplate transactionTemplate;

    /**
     * 재고를 확보하고, 그 확보를 되돌릴 보상 작업을 함께 남긴다.
     *
     * <p>{@code @Transactional}을 쓰지 않고 트랜잭션을 재시도 안쪽에서 직접 여는 이유는,
     * 낙관적 락 충돌이 커밋 시점에 드러나기 때문이다. 두 애노테이션을 같은 메서드에 붙이면
     * 재시도 프록시가 트랜잭션 프록시 안쪽에 놓여 커밋 실패를 보지 못한다.
     *
     * @throws com.study.monolithic_architecture.exception.InsufficientStockException 가용재고 부족 (BR-3)
     */
    @Retryable(
            includes = ConcurrencyFailureException.class,
            maxRetries = 5,
            delay = 20,
            jitter = 20,
            multiplier = 2,
            maxDelay = 300)
    public void reserveFor(String orderNo, Long productId, int quantity) {
        transactionTemplate.executeWithoutResult(status -> {
            if (taskRepository.existsByOrderNoAndType(orderNo, CompensationType.RELEASE_STOCK)) {
                return;
            }
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new ProductNotFoundException(productId));
            product.reserve(quantity);
            taskRepository.save(new CompensationTask(orderNo, CompensationType.RELEASE_STOCK));
        });
    }
}
