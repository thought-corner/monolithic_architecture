package com.study.monolithic_architecture.payment.repository;

import com.study.monolithic_architecture.payment.domain.Payment;
import com.study.monolithic_architecture.payment.domain.PaymentStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 결제 저장소.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * 주문의 결제 기록을 조회한다.
     * 재고 부족으로 실패한 주문에는 결제 기록 자체가 없다. (S2)
     */
    Optional<Payment> findByOrderNo(String orderNo);

    /**
     * 결과가 미확인인 결제들. 조회로 해소해야 할 대상이다. (NFR-05)
     * 정산이 이 목록을 훑는다.
     */
    List<Payment> findAllByStatusOrderByIdAsc(PaymentStatus status, Pageable pageable);
}
