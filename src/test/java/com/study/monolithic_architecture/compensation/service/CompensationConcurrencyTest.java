package com.study.monolithic_architecture.compensation.service;

import com.study.monolithic_architecture.TestcontainersConfiguration;
import com.study.monolithic_architecture.compensation.domain.CompensationProgress;
import com.study.monolithic_architecture.compensation.domain.CompensationTask;
import com.study.monolithic_architecture.compensation.domain.CompensationType;
import com.study.monolithic_architecture.compensation.repository.CompensationTaskRepository;
import com.study.monolithic_architecture.order.repository.OrderStatusHistoryRepository;
import com.study.monolithic_architecture.payment.domain.GatewayStatus;
import com.study.monolithic_architecture.payment.domain.Payment;
import com.study.monolithic_architecture.payment.gateway.PaymentGateway;
import com.study.monolithic_architecture.payment.repository.PaymentRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 같은 보상 작업을 동시에 여러 번 시도해도 되돌리기는 한 번만 일어난다. (§8 R-3)
 *
 * <p>비동기 주문 처리와 정산 스케줄러는 서로 다른 스레드이며, 둘 다 같은 미결 보상을
 * 집을 수 있다. {@code isPending()} 검사만으로는 두 트랜잭션이 모두 통과한다.
 *
 * <p>재고 해제는 Product의 {@code @Version}이 <b>우연히</b> 막아준다. 두 트랜잭션이
 * 같은 상품 행을 갱신하기 때문이다. 그러나 결제 취소는 막아주는 것이 없다.
 * 대행에 취소가 두 번 나가고 이력에도 같은 사건이 두 줄 남는다. (FR-10)
 */
@SpringBootTest(properties = "order.payment-timeout=300ms")
@Import(TestcontainersConfiguration.class)
class CompensationConcurrencyTest {

    private static final int CONCURRENCY = 8;

    @Autowired
    CompensationService compensationService;
    @Autowired
    CompensationTaskRepository taskRepository;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    OrderStatusHistoryRepository historyRepository;

    @MockitoBean
    PaymentGateway paymentGateway;

    @Test
    @DisplayName("여러 스레드가 같은 결제 취소 보상을 집어도 대행 취소는 한 번만 나간다")
    void 같은_보상은_한_번만_수행된다() throws Exception {
        String orderNo = newOrderNo();
        paymentRepository.save(new Payment(orderNo, 30_000L));
        given(paymentGateway.inquire(anyString())).willReturn(GatewayStatus.APPROVED);
        given(paymentGateway.cancel(anyString())).willReturn(GatewayStatus.CANCELLED);

        compensationService.enqueue(orderNo, CompensationType.CANCEL_PAYMENT);
        Long taskId = cancelTask(orderNo).getId();

        attemptConcurrently(taskId);

        verify(paymentGateway, times(1)).cancel(anyString());
        assertThat(cancelHistories(orderNo))
                .as("같은 되돌리기가 두 번 수행되면 이력에도 같은 사건이 두 줄 남는다 (FR-10)")
                .hasSize(1);
        assertThat(cancelTask(orderNo).getProgress()).isEqualTo(CompensationProgress.DONE);
    }

    /**
     * 모든 스레드를 같은 순간 출발시킨다. 예외는 각 스레드 안에서 흡수된다.
     */
    private void attemptConcurrently(Long taskId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CONCURRENCY);
        try {
            for (int i = 0; i < CONCURRENCY; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        compensationService.attempt(taskId);
                    } catch (Exception ignored) {
                        // 실패도 정상 결과다. 검증 대상은 '되돌리기가 몇 번 일어났는가'다.
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdown();
        }
    }

    private List<?> cancelHistories(String orderNo) {
        return historyRepository.findAllByOrderNoOrderByOccurredAtAsc(orderNo).stream()
                .filter(history -> "결제 승인 취소".equals(history.getReason()))
                .toList();
    }

    private CompensationTask cancelTask(String orderNo) {
        return taskRepository.findAllByOrderNo(orderNo).stream()
                .filter(task -> task.getType() == CompensationType.CANCEL_PAYMENT)
                .findFirst()
                .orElseThrow();
    }

    private String newOrderNo() {
        return "ORD-" + UUID.randomUUID();
    }
}
