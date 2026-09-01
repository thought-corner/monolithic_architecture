package com.study.monolithic_architecture.reconciliation.service;

import com.study.monolithic_architecture.TestcontainersConfiguration;
import com.study.monolithic_architecture.compensation.domain.CompensationProgress;
import com.study.monolithic_architecture.compensation.domain.CompensationTask;
import com.study.monolithic_architecture.compensation.domain.CompensationType;
import com.study.monolithic_architecture.compensation.repository.CompensationTaskRepository;
import com.study.monolithic_architecture.compensation.service.CompensationService;
import com.study.monolithic_architecture.order.domain.Order;
import com.study.monolithic_architecture.order.domain.OrderStatus;
import com.study.monolithic_architecture.order.repository.OrderRepository;
import com.study.monolithic_architecture.order.service.OrderProcessingService;
import com.study.monolithic_architecture.order.service.OrderSettlementService;
import com.study.monolithic_architecture.order.service.StockReservationService;
import com.study.monolithic_architecture.product.domain.Product;
import com.study.monolithic_architecture.product.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * <b>언제</b> 보상을 실행해도 되는가. (BR-5, NFR-03)
 *
 * <p>보상 작업은 확보 시점에 PENDING으로 등록된다. 그러나 PENDING은
 * "지금 되돌려라"가 아니라 "실패하면 되돌려야 할 의무가 있다"는 뜻이다.
 * 이 둘을 같은 뜻으로 읽으면 정산이 정상 처리 중인 주문의 확보를 풀어버린다.
 *
 * <p>여기서 고정하는 것은 세 가지다.
 * <ol>
 *   <li>아직 처리 중인 주문의 의무는 실행 대상이 아니다.</li>
 *   <li>확정된 주문의 의무는 사라진다. 확보가 차감으로 바뀌었기 때문이다.</li>
 *   <li>되돌리지 못한 일이 남아 있으면 주문을 실패로 닫지 않는다. 소진(EXHAUSTED)도 포함된다.</li>
 * </ol>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CompensationSchedulingTest {

    @Autowired
    ReconciliationService reconciliationService;
    @Autowired
    OrderSettlementService settlementService;
    @Autowired
    OrderProcessingService processingService;
    @Autowired
    StockReservationService reservationService;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    CompensationTaskRepository taskRepository;
    @Autowired
    CompensationService compensationService;

    /**
     * 다른 시험이 남긴 미결과 섞이지 않도록 넉넉히 가져와 내 것만 추린다.
     */
    private static final int LOOKAHEAD = 1_000;

    @Test
    @DisplayName("아직 처리 중인 주문의 재고 확보를 정산이 풀지 않는다")
    void 처리_중인_주문의_확보는_유지된다() {
        Product product = productRepository.save(new Product("처리중상품", 30_000L, 10));
        Order order = accepted(product, 3, LocalDateTime.now());
        reservationService.reserveFor(order.getOrderNo(), product.getId(), 3);

        reconciliationService.retryPendingCompensations();

        assertThat(reload(product).getReservedQuantity())
                .as("결제 응답을 기다리는 중에 확보가 풀리면, 확정 시점에 차감할 수량이 사라진다")
                .isEqualTo(3);
        assertThat(releaseTask(order.getOrderNo()).getProgress())
                .as("되돌릴 의무는 그대로 남아 있어야 한다. 실행만 미루는 것이다")
                .isEqualTo(CompensationProgress.PENDING);
    }

    @Test
    @DisplayName("종결 데드라인을 넘긴 접수 주문의 보상은 정산이 실행한다")
    void 지연된_주문의_보상은_실행된다() {
        Product product = productRepository.save(new Product("지연상품", 30_000L, 10));
        Order order = accepted(product, 3, LocalDateTime.now().minusHours(1));
        reservationService.reserveFor(order.getOrderNo(), product.getId(), 3);

        reconciliationService.retryPendingCompensations();

        assertThat(reload(product).getReservedQuantity())
                .as("데드라인을 넘긴 접수 주문은 처리가 끊긴 것이다. 붙잡힌 재고를 놓아줘야 한다")
                .isZero();
        assertThat(releaseTask(order.getOrderNo()).getProgress())
                .isEqualTo(CompensationProgress.DONE);
    }

    @Test
    @DisplayName("확정된 주문에는 되돌릴 의무가 남지 않는다")
    void 확정은_확보_의무를_해제한다() {
        Product product = productRepository.save(new Product("확정상품", 30_000L, 10));
        Order order = accepted(product, 5, LocalDateTime.now());
        reservationService.reserveFor(order.getOrderNo(), product.getId(), 5);

        settlementService.confirm(order.getOrderNo());

        assertThat(reload(product).getStockQuantity()).isEqualTo(5);
        assertThat(reload(product).getReservedQuantity()).isZero();
        assertThat(releaseTask(order.getOrderNo()).getProgress())
                .as("확보가 차감으로 바뀌었으므로 되돌릴 것이 없다. 남겨두면 정산이 이미 차감된 수량을 다시 놓아준다")
                .isEqualTo(CompensationProgress.DONE);
    }

    @Test
    @DisplayName("확정된 주문의 잔여 보상이 다른 주문의 확보를 풀지 않는다")
    void 확정_이후_정산이_남의_확보를_풀지_않는다() {
        Product product = productRepository.save(new Product("경합상품", 30_000L, 10));

        Order confirmed = accepted(product, 5, LocalDateTime.now());
        reservationService.reserveFor(confirmed.getOrderNo(), product.getId(), 5);
        settlementService.confirm(confirmed.getOrderNo());

        Order inFlight = accepted(product, 5, LocalDateTime.now());
        reservationService.reserveFor(inFlight.getOrderNo(), product.getId(), 5);

        reconciliationService.retryPendingCompensations();

        assertThat(reload(product).getReservedQuantity())
                .as("확정된 주문의 잔여 보상이 실행되면 남의 확보분이 풀려 가용재고가 부풀고 초과 판매가 된다")
                .isEqualTo(5);
        assertThat(reload(product).getStockQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("되돌리기를 포기한 보상이 남아 있으면 주문을 실패로 닫지 않는다")
    void 소진된_보상이_남으면_종결하지_않는다() {
        // 150_000원은 SimulatedPaymentGateway의 거절 기준(100_000) 이상이라 반드시 거절된다. (BR-6)
        Product product = productRepository.save(new Product("고가상품", 150_000L, 10));
        Order order = accepted(product, 1, LocalDateTime.now());
        reservationService.reserveFor(order.getOrderNo(), product.getId(), 1);
        exhaust(releaseTask(order.getOrderNo()));

        processingService.process(order.getOrderNo());

        assertThat(reload(order).getStatus())
                .as("소진은 '되돌렸다'가 아니라 '되돌리기를 포기했다'다. 그대로 닫으면 확보된 재고가 영영 잠긴다 (BR-5)")
                .isEqualTo(OrderStatus.ACCEPTED);
        assertThat(reload(product).getReservedQuantity()).isEqualTo(1);
    }

    @Test
    @DisplayName("확정된 주문에 남은 보상은 건너뛰지 않고 해소한다")
    void 확정된_주문의_잔여_보상은_해소된다() {
        Product product = productRepository.save(new Product("잔여상품", 30_000L, 10));
        Order order = accepted(product, 2, LocalDateTime.now());
        reservationService.reserveFor(order.getOrderNo(), product.getId(), 2);
        settlementService.confirm(order.getOrderNo());

        // 확정 직전에 정산이 등록해 둔 취소 보상. 확정으로 되돌릴 이유가 사라진 작업이다.
        compensationService.enqueue(order.getOrderNo(), CompensationType.CANCEL_PAYMENT);

        reconciliationService.retryPendingCompensations();

        assertThat(cancelTask(order.getOrderNo()).getProgress())
                .as("건너뛰기만 하면 이 작업은 영원히 미결로 남아 매 주기 조회되고, "
                        + "배치 상한을 둔 뒤에는 뒤에 있는 진짜 미결 보상을 굶긴다")
                .isEqualTo(CompensationProgress.DONE);
        assertThat(reload(product).getReservedQuantity())
                .as("해소는 표시만 바꾼다. 확정된 주문의 재고를 다시 건드리면 안 된다")
                .isZero();
    }

    @Test
    @DisplayName("배치 상한이 걸려도 먼저 등록된 미결 보상이 뒤로 밀리지 않는다")
    void 오래된_미결이_새_작업에_밀리지_않는다() {
        Product product = productRepository.save(new Product("순서상품", 30_000L, 10));

        // 이미 한 번 시도한, 처리가 끊긴 주문의 보상. 정산이 이어받아야 할 대상이다.
        Order stalled = accepted(product, 1, LocalDateTime.now().minusHours(1));
        reservationService.reserveFor(stalled.getOrderNo(), product.getId(), 1);
        CompensationTask stalledTask = releaseTask(stalled.getOrderNo());
        stalledTask.recordAttempt(LocalDateTime.now().minusHours(1));
        taskRepository.save(stalledTask);

        // 그 뒤에 접수돼 아직 처리 중인 주문들. 확보 성공 순간부터 미결로 등록된다.
        List<String> inFlight = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Order order = accepted(product, 1, LocalDateTime.now());
            reservationService.reserveFor(order.getOrderNo(), product.getId(), 1);
            inFlight.add(order.getOrderNo());
        }

        List<String> mine = compensationService.findPending(LOOKAHEAD).stream()
                .map(CompensationTask::getOrderNo)
                .filter(orderNo -> orderNo.equals(stalled.getOrderNo()) || inFlight.contains(orderNo))
                .toList();

        assertThat(mine).first()
                .as("처리 중인 주문의 작업이 앞자리를 차지하면, 상한 아래에서 정작 재시도가 "
                        + "필요한 보상이 배치에 들어오지 못해 확보된 재고가 영영 풀리지 않는다")
                .isEqualTo(stalled.getOrderNo());
    }

    @Test
    @DisplayName("주문이 없는 보상 작업은 매 주기 건너뛰지 않고 포기로 표시한다")
    void 주문이_없는_보상은_포기로_표시된다() {
        CompensationTask orphan = taskRepository.save(
                new CompensationTask("ORD-ORPHAN-" + UUID.randomUUID(), CompensationType.RELEASE_STOCK));

        reconciliationService.retryPendingCompensations();

        assertThat(taskRepository.findById(orphan.getId()).orElseThrow().getProgress())
                .as("건너뛰기만 하면 배치 상한 아래에서 이 작업이 한 칸을 영구히 차지해, "
                        + "뒤에 있는 진짜 미결 보상이 영영 처리되지 않는다")
                .isEqualTo(CompensationProgress.EXHAUSTED);
    }

    @Test
    @DisplayName("보상 한 건의 실패가 정산 주기 전체를 멈추지 않는다")
    void 보상_실패가_밖으로_새지_않는다() {
        // 시도 기록 단계에서 실패하는 경우를 세운다. 잠금 대기 초과·커넥션 장애도 같은 경로다.
        assertThatCode(() -> compensationService.attempt(Long.MAX_VALUE))
                .as("여기서 예외가 새면 같은 주기의 다른 미결 보상까지 통째로 건너뛴다")
                .doesNotThrowAnyException();
    }

    private Order accepted(Product product, int quantity, LocalDateTime acceptedAt) {
        String suffix = UUID.randomUUID().toString();
        return orderRepository.save(new Order("ORD-" + suffix, "REQ-" + suffix,
                product.getId(), quantity, product.getPrice(), acceptedAt));
    }

    private void exhaust(CompensationTask task) {
        task.markExhausted();
        taskRepository.save(task);
    }

    private CompensationTask cancelTask(String orderNo) {
        return taskRepository.findAllByOrderNo(orderNo).stream()
                .filter(task -> task.getType() == CompensationType.CANCEL_PAYMENT)
                .findFirst()
                .orElseThrow();
    }

    private CompensationTask releaseTask(String orderNo) {
        return taskRepository.findAllByOrderNo(orderNo).stream()
                .filter(task -> task.getType() == CompensationType.RELEASE_STOCK)
                .findFirst()
                .orElseThrow();
    }

    private Product reload(Product product) {
        return productRepository.findById(product.getId()).orElseThrow();
    }

    private Order reload(Order order) {
        return orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
    }
}
