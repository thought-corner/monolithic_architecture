package com.study.monolithic_architecture.order.service;

import com.study.monolithic_architecture.compensation.domain.CompensationType;
import com.study.monolithic_architecture.compensation.service.CompensationService;
import com.study.monolithic_architecture.order.domain.FailureReason;
import com.study.monolithic_architecture.order.domain.Order;
import com.study.monolithic_architecture.order.domain.OrderAcceptedEvent;
import com.study.monolithic_architecture.order.domain.OrderNotFoundException;
import com.study.monolithic_architecture.order.domain.OrderStatus;
import com.study.monolithic_architecture.order.repository.OrderRepository;
import com.study.monolithic_architecture.payment.domain.PaymentOutcome;
import com.study.monolithic_architecture.payment.service.PaymentService;
import com.study.monolithic_architecture.product.domain.InsufficientStockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 접수된 주문을 처리한다. 재고 확보 → 결제 → 확정 또는 보상 후 실패. (FR-05·06·07)
 *
 * <p>이 클래스에는 트랜잭션이 없다. 여기는 순서만 잡고, 커밋 경계는 각 단계가 스스로 가진다.
 * 확보와 결제를 한 트랜잭션에 묶으면 롤백이 확보를 지워버려
 * "확보됐다가 되돌아갔다"는 사실이 이력에 남지 않는다. (S3)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderProcessingService {

    private final OrderRepository orderRepository;
    private final StockReservationService reservationService;
    private final PaymentService paymentService;
    private final CompensationService compensationService;
    private final OrderSettlementService settlementService;
    private final OrderHistoryService historyService;

    /**
     * 접수가 커밋된 뒤에 처리를 시작한다. 접수 응답은 이 처리를 기다리지 않는다. (NFR-01)
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderAccepted(OrderAcceptedEvent event) {
        try {
            process(event.orderNo());
        } catch (RuntimeException e) {
            // 여기서 새어나가면 아무도 받지 못한다. 주문은 ACCEPTED로 남고 정산이 이어받는다.
            log.error("주문 처리 실패: {}", event.orderNo(), e);
        }
    }

    public void process(String orderNo) {
        Order order = order(orderNo);

        // 정산이 먼저 종결시켰을 수 있다. 그 위에 덧칠하면 이미 닫힌 주문의 재고를 다시 건드린다.
        if (order.getStatus().isTerminal()) {
            log.info("이미 종결된 주문이라 처리하지 않는다: {} ({})", orderNo, order.getStatus());
            return;
        }

        // 1. 재고 확보 (FR-05). 확보와 되돌릴 의무가 함께 커밋된다.
        try {
            reservationService.reserveFor(orderNo, order.getProductId(), order.getQuantity());
            historyService.recordWithinAccepted(orderNo, "재고 확보");
        } catch (InsufficientStockException e) {
            // 확보가 롤백됐으므로 되돌릴 것도, 결제 기록도 없다. (S2)
            settlementService.fail(orderNo, FailureReason.OUT_OF_STOCK);
            return;
        }

        // 2. 결제 (FR-06)
        PaymentOutcome outcome = paymentService.pay(orderNo, order.getOrderAmount());
        if (!stillAccepted(orderNo)) {
            // 결제를 기다리는 사이 정산이 종결시켰다. 보상은 정산이 이어받는다.
            log.warn("결제 중 주문이 종결됐다. 보상을 정산에 넘긴다: {}", orderNo);
            return;
        }

        switch (outcome) {
            case APPROVED -> settlementService.confirm(orderNo);
            case DECLINED -> compensateAndFail(orderNo, FailureReason.PAYMENT_DECLINED, false);
            case TIMEOUT -> compensateAndFail(orderNo, FailureReason.TIMEOUT, true);
        }
    }

    /**
     * 되돌린 뒤에 실패로 종결한다. (BR-5)
     *
     * <p>재고 확보를 되돌리는 일은 확보 시점에 이미 등록돼 있다. 여기서는 결제 취소만
     * 더하고 전부 실행한다. 하나라도 남으면 종결하지 않는다. 주문은 ACCEPTED에
     * 머물고 정산이 재시도한다.
     *
     * <p>종결 가능 여부는 미결(PENDING)이 아니라 <b>미해소(hasUnresolved)</b>로 판정한다.
     * 재시도를 소진한(EXHAUSTED) 작업은 '되돌렸다'가 아니라 '되돌리기를 포기했다'이며,
     * 그 상태로 닫으면 확보한 재고가 영영 풀리지 않는다. 사람이 들여다볼 때까지
     * 접수 상태로 남는 것이 옳다. (§8 R-6)
     *
     * @param mayHaveApproved 결제가 승인됐을 수도 있는가. 타임아웃이면 참이며,
     *                        조회로 확인한 뒤에만 실제 취소가 나간다.
     */
    private void compensateAndFail(String orderNo, FailureReason reason, boolean mayHaveApproved) {
        if (mayHaveApproved) {
            compensationService.enqueue(orderNo, CompensationType.CANCEL_PAYMENT);
        }
        compensationService.runPending(orderNo);

        if (compensationService.hasUnresolved(orderNo)) {
            log.warn("되돌리지 못한 일이 남아 종결을 미룬다: {} ({})", orderNo, reason);
            return;
        }
        settlementService.fail(orderNo, reason);
    }

    private boolean stillAccepted(String orderNo) {
        return order(orderNo).getStatus() == OrderStatus.ACCEPTED;
    }

    private Order order(String orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new OrderNotFoundException(orderNo));
    }
}
