package com.study.monolithic_architecture.service;

import com.study.monolithic_architecture.TestcontainersConfiguration;
import com.study.monolithic_architecture.constants.GatewayStatus;
import com.study.monolithic_architecture.constants.PaymentStatus;
import com.study.monolithic_architecture.constants.PaymentOutcome;
import com.study.monolithic_architecture.constants.PaymentVerification;
import com.study.monolithic_architecture.domain.Payment;
import com.study.monolithic_architecture.exception.PaymentTimeoutException;
import com.study.monolithic_architecture.gateway.PaymentGateway;
import com.study.monolithic_architecture.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 결제 결과를 모르는 상태(in-doubt)를 어떻게 다루는지 검증한다. (§8 R-1)
 *
 * <p>여기서 지키려는 것은 하나다. <b>모르는 것을 거절로 단정하지 않는다.</b>
 * 단정해버리면 뒤늦게 승인된 대금이 취소되지 않고 그대로 남는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentInDoubtTest {

    /** 결제 시간 예산(5초)보다 넉넉하되, 실패해도 시험이 오래 매달리지 않는 상한. */
    private static final Duration BUDGET_MARGIN = Duration.ofSeconds(10);

    @Autowired PaymentService paymentService;
    @Autowired PaymentRepository paymentRepository;

    @MockitoBean PaymentGateway paymentGateway;

    @Test
    @DisplayName("조회에서 승인 이력이 없다고 나와도 거절로 확정하지 않는다")
    void 승인_이력_없음은_거절이_아니다() {
        String orderNo = newOrderNo();
        paymentRepository.save(new Payment(orderNo, 30_000L));
        given(paymentGateway.inquire(anyString())).willReturn(GatewayStatus.NOT_FOUND);

        PaymentVerification result = paymentService.verify(orderNo);

        assertThat(result).isEqualTo(PaymentVerification.UNRESOLVED);
        assertThat(paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .as("아직 모르는 결제를 DECLINED로 닫으면 뒤늦은 승인이 취소되지 않는다")
                .isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("결과를 모르는 채로 취소를 시도하면 실패시켜 보상이 미결로 남게 한다")
    void 미확인_상태에서는_취소를_확정하지_않는다() {
        String orderNo = newOrderNo();
        paymentRepository.save(new Payment(orderNo, 30_000L));
        given(paymentGateway.inquire(anyString())).willReturn(GatewayStatus.NOT_FOUND);

        assertThatThrownBy(() -> paymentService.cancel(orderNo))
                .isInstanceOf(PaymentTimeoutException.class);

        assertThat(paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    @DisplayName("뒤늦게 승인이 확인되면 취소가 실제로 나간다")
    void 뒤늦은_승인은_취소된다() {
        String orderNo = newOrderNo();
        paymentRepository.save(new Payment(orderNo, 30_000L));
        given(paymentGateway.inquire(anyString())).willReturn(GatewayStatus.APPROVED);
        given(paymentGateway.cancel(anyString())).willReturn(GatewayStatus.CANCELLED);

        paymentService.cancel(orderNo);

        assertThat(paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    @DisplayName("이미 결과가 확정된 결제는 대행을 다시 부르지 않는다")
    void 종결된_결제는_재요청하지_않는다() {
        String orderNo = newOrderNo();
        Payment payment = paymentRepository.save(new Payment(orderNo, 30_000L));
        payment.decline();
        paymentRepository.save(payment);

        // approve()를 부르면 Payment.requireUnknown()에서 예외가 났었다
        assertThat(paymentService.pay(orderNo, 30_000L))
                .isEqualTo(com.study.monolithic_architecture.constants.PaymentOutcome.DECLINED);
    }

    @Test
    @DisplayName("결제를 기다리는 사이 정산이 같은 결과를 확정해도 승인이 뒤집히지 않는다")
    void 동시_확인이_승인을_예외로_만들지_않는다() throws Exception {
        String orderNo = newOrderNo();
        CountDownLatch reachedGateway = new CountDownLatch(1);
        CountDownLatch settlementDone = new CountDownLatch(1);

        // 대행은 승인하지만, 정산이 먼저 조회로 확정할 시간을 준다.
        given(paymentGateway.approve(anyString(), anyLong())).willAnswer(invocation -> {
            reachedGateway.countDown();
            settlementDone.await(BUDGET_MARGIN.toMillis(), TimeUnit.MILLISECONDS);
            return GatewayStatus.APPROVED;
        });
        given(paymentGateway.inquire(anyString())).willReturn(GatewayStatus.APPROVED);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<PaymentOutcome> paying = pool.submit(() -> paymentService.pay(orderNo, 30_000L));
            assertThat(reachedGateway.await(BUDGET_MARGIN.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

            // 정산이 하는 일: 미확인 결제를 조회로 해소해 APPROVED로 확정한다.
            paymentService.verify(orderNo);
            settlementDone.countDown();

            assertThat(paying.get(BUDGET_MARGIN.toSeconds(), TimeUnit.SECONDS))
                    .as("정산이 먼저 같은 결론에 도달했다는 이유로 주문 처리가 끊기면, "
                            + "승인된 결제가 뒤늦게 취소되고 정상 주문이 실패로 닫힌다")
                    .isEqualTo(PaymentOutcome.APPROVED);
        } finally {
            settlementDone.countDown();
            pool.shutdownNow();
        }

        assertThat(paymentRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.APPROVED);
    }

    private String newOrderNo() {
        return "ORD-" + UUID.randomUUID();
    }
}
