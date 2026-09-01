package com.study.monolithic_architecture.service;

import com.study.monolithic_architecture.constants.FailureReason;
import com.study.monolithic_architecture.constants.OrderStatus;
import com.study.monolithic_architecture.constants.CompensationType;
import com.study.monolithic_architecture.constants.PaymentStatus;
import com.study.monolithic_architecture.constants.PaymentVerification;
import com.study.monolithic_architecture.domain.CompensationTask;
import com.study.monolithic_architecture.domain.Order;
import com.study.monolithic_architecture.domain.Payment;
import com.study.monolithic_architecture.repository.OrderRepository;
import com.study.monolithic_architecture.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 정산. 중단되거나 미뤄진 처리를 해소한다. (NFR-03)
 *
 * <p>세 가지를 훑는다.
 * <ol>
 *   <li>결과 미확인 결제 → 조회로 해소한다.</li>
 *   <li>미결 보상 → 다시 시도한다.</li>
 *   <li>보상이 모두 끝났는데 아직 종결되지 않은 주문 → 실패로 종결한다. (BR-5)</li>
 * </ol>
 *
 * <p><b>언제 도는지는 모른다.</b> 그것은
 * {@code task.ReconciliationScheduler}의 몫이다. 덕분에 시험은 주기를 기다리지 않고
 * 이 메서드를 직접 부르면 된다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    /**
     * 접수부터 종결까지의 상한. 이 시간을 넘긴 ACCEPTED는 미결 보상이 남았다는 신호다.
     *
     * <p>설정이 아니라 상수인 이유는 두 가지다. 시험은 Clock 주입으로 하므로
     * 외부화할 필요가 없고, 결제 타임아웃과 독립적으로 바뀌면
     * '결제 타임아웃 &lt; 종결 데드라인' 관계가 조용히 깨진다.
     */
    static final Duration SETTLEMENT_DEADLINE = Duration.ofSeconds(30);

    /**
     * 한 주기가 훑을 최대 건수.
     *
     * <p>정산은 고정 주기로 돌므로 한 주기가 다음 주기 안에 끝나야 한다. 상한이 없으면
     * 미결이 쌓일수록 주기가 길어지고, 결국 정산이 사실상 진행되지 않는다.
     * 처리하지 못한 나머지는 다음 주기가 이어받으므로 정확성은 영향받지 않는다.
     */
    static final int BATCH_SIZE = 200;

    private static final Pageable BATCH = PageRequest.of(0, BATCH_SIZE);

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final CompensationService compensationService;
    private final OrderSettlementService settlementService;
    private final Clock clock;

    public void reconcile() {
        resolveUnknownPayments();
        retryPendingCompensations();
        settleCompensatedOrders();
    }

    /**
     * 결과를 모르는 결제는 조회로 해소한다. 취소를 먼저 보내지 않는다.
     *
     * <p>단계별로 나눠 둔 것은 시험이 한 단계만 따로 확인할 수 있게 하기 위해서다.
     */
    void resolveUnknownPayments() {
        List<Payment> unknown = paymentRepository.findAllByStatusOrderByIdAsc(
                PaymentStatus.UNKNOWN, BATCH);
        for (Payment payment : unknown) {
            try {
                PaymentVerification verification = paymentService.verify(payment.getOrderNo());
                if (verification == PaymentVerification.UNRESOLVED) {
                    log.info("결제 결과가 아직 확인되지 않았다. 다음 주기에 다시 묻는다: {}",
                            payment.getOrderNo());
                }
            } catch (RuntimeException e) {
                log.warn("결제 조회 실패: {}", payment.getOrderNo(), e);
            }
        }
    }

    /**
     * 미결 보상을 다시 시도한다. 단, <b>되돌려야 할 주문의 것만</b> 시도한다.
     *
     * <p>보상 작업은 확보에 성공한 순간 PENDING으로 등록된다. 그것은 "지금 되돌려라"가 아니라
     * "실패하면 되돌려야 할 의무가 있다"는 뜻이다. 이 구분 없이 PENDING 전체를 실행하면,
     * 아직 결제 응답을 기다리는 정상 주문의 확보까지 풀어버린다. 그 주문은 승인이 돌아온 뒤
     * 차감할 수량을 잃고, 그 사이 다른 주문이 확보를 갖고 있었다면 남의 확보분이 대신
     * 소진되어 초과 판매로 번진다.
     *
     * <p>되돌려야 할 주문의 기준은 {@link #settleCompensatedOrders()}와 같은
     * {@link #SETTLEMENT_DEADLINE}이다. 두 판정이 갈리면 '정산은 데드라인을 넘긴 주문만
     * 손댄다'는 규칙 자체가 성립하지 않는다.
     */
    void retryPendingCompensations() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<CompensationTask> pending = compensationService.findPending(BATCH_SIZE);
        Map<String, Order> orders = ordersOf(pending);

        for (CompensationTask task : pending) {
            Order order = orders.get(task.getOrderNo());
            if (order == null) {
                // 되돌릴 대상이 없다. 건너뛰기만 하면 매 주기 배치 한 칸을 그대로 차지한다.
                log.error("보상 작업이 가리키는 주문이 없다. 운영 확인이 필요하다: {}", task.getOrderNo());
                compensationService.abandon(task.getId());
                continue;
            }
            if (order.getStatus() == OrderStatus.CONFIRMED) {
                // 되돌릴 이유가 사라졌다. 건너뛰기만 하면 영원히 미결로 남아 배치를 굶긴다.
                compensationService.dischargeAll(order.getOrderNo());
                continue;
            }
            if (needsCompensation(order, now)) {
                compensationService.attempt(task.getId());
            }
        }
    }

    /**
     * 이번 배치가 가리키는 주문을 <b>한 번에</b> 읽는다.
     *
     * <p>작업마다 되물으면 미결 건수만큼 쿼리가 늘어난다. RELEASE_STOCK은 확보 시점부터
     * 미결이므로 평상시에도 그 건수는 처리 중인 주문 수와 같다.
     */
    private Map<String, Order> ordersOf(List<CompensationTask> tasks) {
        Set<String> orderNos = tasks.stream()
                .map(CompensationTask::getOrderNo)
                .collect(Collectors.toSet());
        if (orderNos.isEmpty()) {
            return Map.of();
        }
        return orderRepository.findAllByOrderNoIn(orderNos).stream()
                .collect(Collectors.toMap(Order::getOrderNo, Function.identity()));
    }

    /**
     * 지금 되돌려야 하는 주문인가.
     *
     * <p>데드라인 안쪽의 접수 주문은 아니다. 아직 처리 중일 뿐이며, 실패로 끝나면 그 경로가
     * 직접 보상을 부른다. 남는 것은 실패로 닫힌 주문과 데드라인을 넘긴 접수 주문뿐이다.
     * (확정된 주문은 호출부에서 이미 해소했다.)
     */
    private boolean needsCompensation(Order order, LocalDateTime now) {
        return order.getStatus() == OrderStatus.FAILED
                || order.isStale(SETTLEMENT_DEADLINE, now);
    }

    /**
     * 종결 데드라인을 넘겼고 미결 보상이 없는 주문을 실패로 종결한다.
     * 실패 사유는 결제 상태에서 유도한다. 새 필드를 두지 않기 위해서다.
     */
    void settleCompensatedOrders() {
        LocalDateTime threshold = LocalDateTime.now(clock).minus(SETTLEMENT_DEADLINE);
        List<Order> stale = orderRepository.findAllByStatusAndAcceptedAtBeforeOrderByIdAsc(
                OrderStatus.ACCEPTED, threshold, BATCH);

        for (Order order : stale) {
            if (compensationService.hasUnresolved(order.getOrderNo())) {
                // 소진된 보상도 '되돌리지 못한 일'이다. 이 상태로 닫으면 확보한 재고가 영영 남는다.
                continue;
            }
            try {
                settleIfSafe(order.getOrderNo());
            } catch (RuntimeException e) {
                log.warn("지연 주문 종결 실패: {}", order.getOrderNo(), e);
            }
        }
    }

    /**
     * 되돌릴 것이 남지 않았음을 확인한 뒤에만 종결한다. (BR-5)
     *
     * <p>결제 결과를 아직 모르거나 승인이 확인된 주문은 이번 주기에 종결하지 않는다.
     * 모른 채 실패로 닫으면 뒤늦게 승인된 대금이 취소되지 않고, 승인된 채 닫으면
     * 취소가 나가지 않는다. 둘 다 돈이 남는 결과다.
     */
    private void settleIfSafe(String orderNo) {
        PaymentStatus status = paymentRepository.findByOrderNo(orderNo)
                .map(Payment::getStatus)
                .orElse(null);

        if (status == PaymentStatus.UNKNOWN) {
            log.info("결제 결과 미확인이라 종결을 미룬다: {}", orderNo);
            return;
        }
        if (status == PaymentStatus.APPROVED) {
            log.warn("승인된 결제가 남아 있어 취소를 먼저 등록한다: {}", orderNo);
            compensationService.enqueue(orderNo, CompensationType.CANCEL_PAYMENT);
            compensationService.runPending(orderNo);
            return;
        }
        settlementService.fail(orderNo, reasonOf(status));
    }

    /**
     * 실패 사유. 결제가 거절된 것이 확인된 경우에만 PAYMENT_DECLINED이고,
     * 나머지는 종결 데드라인을 넘겼다는 뜻의 TIMEOUT이다.
     */
    private FailureReason reasonOf(PaymentStatus status) {
        return status == PaymentStatus.DECLINED
                ? FailureReason.PAYMENT_DECLINED
                : FailureReason.TIMEOUT;
    }
}
