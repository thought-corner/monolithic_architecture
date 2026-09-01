package com.study.monolithic_architecture.repository;

import com.study.monolithic_architecture.domain.Product;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 상품 저장소.
 *
 * <p>FR-01(목록)은 {@code findAll()}, FR-02(상세)는 {@code findById()}로 충분하므로
 * 따로 선언하지 않는다.
 *
 * <p>재고 확보의 동시성은 Product의 {@code @Version}(낙관적 락)이 막는다.
 * 충돌 시 OptimisticLockingFailureException이 나며, 재시도는 서비스 계층의 몫이다.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
}
