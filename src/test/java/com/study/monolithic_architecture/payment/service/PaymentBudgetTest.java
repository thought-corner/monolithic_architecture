package com.study.monolithic_architecture.payment.service;

import com.study.monolithic_architecture.TestcontainersConfiguration;
import com.study.monolithic_architecture.compensation.domain.CompensationProgress;
import com.study.monolithic_architecture.compensation.domain.CompensationTask;
import com.study.monolithic_architecture.compensation.domain.CompensationType;
import com.study.monolithic_architecture.compensation.repository.CompensationTaskRepository;
import com.study.monolithic_architecture.compensation.service.CompensationService;
import com.study.monolithic_architecture.payment.domain.GatewayStatus;
import com.study.monolithic_architecture.payment.domain.Payment;
import com.study.monolithic_architecture.payment.domain.PaymentStatus;
import com.study.monolithic_architecture.payment.domain.PaymentTimeoutException;
import com.study.monolithic_architecture.payment.domain.PaymentVerification;
import com.study.monolithic_architecture.payment.gateway.PaymentGateway;
import com.study.monolithic_architecture.payment.repository.PaymentRepository;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 결제 대행으로 나가는 <b>모든</b> 호출에 시간 예산이 걸려 있는가. (NFR-05)
 *
 * <p>승인에만 예산을 걸면 조회와 취소가 무한정 매달린다. 이 둘은 보상 경로에서
 * 불리며, 보상은 단일 스레드 스케줄러가 돌린다. 한 번 매달리면 그 뒤의 모든
 * 정산 주기가 실행되지 않는다.
 *
 * <p>여기서는 실제로 응답하지 않는 대행을 세운다. 시계를 주입해 흉내낼 수 없는
 * 종류의 실패이기 때문이다. 걸어 둔 래치는 검증이 끝난 뒤 반드시 풀어 스레드를 회수한다.
 */
@SpringBootTest(properties = "order.payment-timeout=300ms")
@Import(TestcontainersConfiguration.class)
class PaymentBudgetTest {

    /**
     * 예산(300ms)을 확실히 넘기되, 실패해도 시험이 오래 매달리지 않는 상한.
     */
    private static final Duration HANG = Duration.ofSeconds(5);

    /**
     * 예산이 지켜졌다면 이 안에 돌아와야 한다. 예산의 몇 배를 줘도 무응답과는 구분된다.
     */
    private static final Duration BUDGET_TOLERANCE = Duration.ofSeconds(3);

    @Autowired
    PaymentService paymentService;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    CompensationService compensationService;
    @Autowired
    CompensationTaskRepository taskRepository;

    @MockitoBean
    PaymentGateway paymentGateway;

    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void releaseGateway() {
        release.countDown();
    }

    @Test
    @DisplayName("조회가 응답하지 않으면 예산 안에서 포기하고 미확인으로 남긴다")
    void 응답_없는_조회는_예산_안에서_끊긴다() {
        String orderNo = newOrderNo();
        paymentRepository.save(new Payment(orderNo, 30_000L));
        given(paymentGateway.inquire(anyString())).willAnswer(invocation -> hang());

        PaymentVerification verification = withinTolerance(() -> paymentService.verify(orderNo));

        assertThat(verification)
                .as("응답이 없는 것은 '거절'이 아니라 '아직 모른다'다. 다음 주기가 다시 묻는다")
                .isEqualTo(PaymentVerification.UNRESOLVED);
        assertThat(status(orderNo)).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("취소가 응답하지 않으면 예산 안에서 끊고 보상을 미결로 남긴다")
    void 응답_없는_취소는_예산_안에서_끊긴다() {
        String orderNo = newOrderNo();
        paymentRepository.save(new Payment(orderNo, 30_000L));
        given(paymentGateway.inquire(anyString())).willReturn(GatewayStatus.APPROVED);
        given(paymentGateway.cancel(anyString())).willAnswer(invocation -> hang());

        withinTolerance(() -> assertThatThrownBy(() -> paymentService.cancel(orderNo))
                .isInstanceOf(PaymentTimeoutException.class));

        assertThat(status(orderNo))
                .as("취소가 반영됐는지 모르는 채 CANCELLED로 닫으면, 승인된 대금이 남아도 아무도 되돌리지 않는다")
                .isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    @DisplayName("취소가 실패해도 조회로 확인한 승인 기록은 남는다")
    void 조회로_확인한_사실은_보상_실패와_함께_사라지지_않는다() {
        String orderNo = newOrderNo();
        paymentRepository.save(new Payment(orderNo, 30_000L));
        given(paymentGateway.inquire(anyString())).willReturn(GatewayStatus.APPROVED);
        given(paymentGateway.cancel(anyString())).willAnswer(invocation -> hang());

        compensationService.enqueue(orderNo, CompensationType.CANCEL_PAYMENT);
        compensationService.attempt(cancelTask(orderNo).getId());

        assertThat(status(orderNo))
                .as("대행이 '승인됐다'고 알려준 것은 관찰된 사실이다. 우리 쪽 취소 실패로 되돌아가면 "
                        + "다음 주기가 같은 조회를 다시 보내야 하고 재시도 상한만 소모된다")
                .isEqualTo(PaymentStatus.APPROVED);
        assertThat(cancelTask(orderNo).getProgress())
                .as("취소는 확인되지 않았으므로 보상은 미결로 남아야 한다")
                .isEqualTo(CompensationProgress.PENDING);
    }

    private CompensationTask cancelTask(String orderNo) {
        return taskRepository.findAllByOrderNo(orderNo).stream()
                .filter(task -> task.getType() == CompensationType.CANCEL_PAYMENT)
                .findFirst()
                .orElseThrow();
    }

    /**
     * 대행이 응답하지 않는 상황. 시험이 끝나면 래치가 풀려 스레드가 회수된다.
     */
    private GatewayStatus hang() throws InterruptedException {
        release.await(HANG.toMillis(), TimeUnit.MILLISECONDS);
        return GatewayStatus.APPROVED;
    }

    /**
     * 시간 예산이 실제로 지켜졌는지 벽시계로 확인한다. 무응답과 구분되는 유일한 관찰점이다.
     */
    private <T> T withinTolerance(java.util.function.Supplier<T> call) {
        long startedAt = System.nanoTime();
        T result = call.get();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        assertThat(elapsed)
                .as("시간 예산이 걸려 있지 않으면 대행이 응답할 때까지 스케줄러가 통째로 멈춘다")
                .isLessThan(BUDGET_TOLERANCE);
        return result;
    }

    private PaymentStatus status(String orderNo) {
        return paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus();
    }

    private String newOrderNo() {
        return "ORD-" + UUID.randomUUID();
    }
}
