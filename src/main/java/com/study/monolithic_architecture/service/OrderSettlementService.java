package com.study.monolithic_architecture.service;

import com.study.monolithic_architecture.constants.FailureReason;
import com.study.monolithic_architecture.constants.OrderStatus;
import com.study.monolithic_architecture.domain.Order;
import com.study.monolithic_architecture.exception.OrderNotFoundException;
import com.study.monolithic_architecture.repository.OrderRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 주문의 종결. 확정과 실패 둘뿐이며 여기서만 일어난다. (BR-4)
 *
 * <p>오케스트레이션(OrderProcessingService)과 분리한 이유는 종결이 원자적이어야 하기 때문이다.
 * 확정은 재고 차감과 상태 전이가 한 트랜잭션에서 함께 일어나야 BR-7이 깨지지 않는다.
 */
@Service
@RequiredArgsConstructor
public class OrderSettlementService {

    private final OrderRepository orderRepository;
    private final StockService stockService;
    private final CompensationService compensationService;
    private final OrderHistoryService historyService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * 재고를 차감하고 주문을 확정한다.
     * 총재고 감소와 확정 수량 증가가 같은 트랜잭션에서 일어나므로 BR-7이 유지된다.
     *
     * <p><b>되돌릴 의무 해제가 차감보다 먼저다.</b> 순서가 취향이 아니라 잠금 순서다.
     * 보상({@code CompensationExecutor.perform})은 compensation_tasks를 {@code FOR UPDATE}로
     * 잠근 뒤 products를 갱신한다. 여기서 products를 먼저 건드리면 두 트랜잭션이 같은 두 행을
     * 반대 순서로 잠가 교착이 된다. JPA는 UPDATE를 flush 시점에 <b>영속성 컨텍스트에 올라온
     * 순서대로</b> 내보내므로, 잠금 순서를 맞추려면 여기서 <b>먼저 읽어야</b> 한다.
     *
     * <p>의무 해제를 같은 트랜잭션에 두는 이유는 따로 있다. 차감이 커밋됐는데 RELEASE_STOCK이
     * 미결로 남으면 정산이 이미 차감된 수량을 다시 놓아주고, 그 시점에 다른 주문이 확보를
     * 갖고 있으면 남의 확보분이 대신 풀린다. 둘 사이에 창이 생기면 안 된다.
     *
     * <p>확보와 마찬가지로 트랜잭션을 재시도 안쪽에서 직접 연다.
     * {@code @Transactional}을 붙이면 재시도가 트랜잭션 안쪽에 놓여 커밋 실패를 보지 못한다.
     *
     * <p>재시도 대상은 낙관적 락에 한정하지 않는다. 보상이 쥔 작업 행을 기다리다 교착 패자가
     * 되거나 잠금 대기가 만료되면 {@code PessimisticLockingFailureException}이 오는데,
     * 이것은 {@code OptimisticLockingFailureException}의 형제라 따로 잡히지 않는다.
     * 둘 다 '지금은 경합했으니 다시 하면 된다'는 뜻이므로 공통 부모로 받는다.
     */
    @Retryable(includes = ConcurrencyFailureException.class,
            maxRetries = 5, delay = 20, jitter = 20, multiplier = 2, maxDelay = 300)
    public void confirm(String orderNo) {
        transactionTemplate.executeWithoutResult(status -> {
            Order order = order(orderNo);
            // 보상과 같은 순서로 잠근다: compensation_tasks → products.
            compensationService.dischargeAll(orderNo);
            stockService.deduct(order.getProductId(), order.getQuantity());
            order.confirm(LocalDateTime.now(clock));
            historyService.record(orderNo, OrderStatus.ACCEPTED, OrderStatus.CONFIRMED, "결제 승인");
        });
    }

    /**
     * 주문을 실패로 종결한다.
     * 호출 전에 미결 보상이 없음을 반드시 확인해야 한다. (BR-5)
     */
    @Transactional
    public void fail(String orderNo, FailureReason reason) {
        Order order = order(orderNo);
        order.fail(reason, LocalDateTime.now(clock));
        historyService.record(orderNo, OrderStatus.ACCEPTED, OrderStatus.FAILED, reason.name());
    }

    private Order order(String orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new OrderNotFoundException(orderNo));
    }
}
