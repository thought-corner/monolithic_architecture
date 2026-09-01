package com.study.monolithic_architecture.payment.service;

import com.study.monolithic_architecture.compensation.service.CompensationExecutor;
import com.study.monolithic_architecture.payment.config.ProcessingProperties;
import com.study.monolithic_architecture.payment.domain.GatewayStatus;
import com.study.monolithic_architecture.payment.domain.Payment;
import com.study.monolithic_architecture.payment.domain.PaymentOutcome;
import com.study.monolithic_architecture.payment.domain.PaymentStatus;
import com.study.monolithic_architecture.payment.domain.PaymentTimeoutException;
import com.study.monolithic_architecture.payment.domain.PaymentVerification;
import com.study.monolithic_architecture.payment.gateway.PaymentGateway;
import com.study.monolithic_architecture.payment.repository.PaymentRepository;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제 요청·조회·취소. 결제 대행으로 나가는 유일한 통로다.
 *
 * <p>이 클래스는 일부러 트랜잭션을 걸지 않는다. 외부 호출을 트랜잭션 안에 두면
 * 응답을 기다리는 동안 커넥션을 붙잡고, 타임아웃이 나면 "요청을 보냈다"는 사실까지
 * 함께 롤백된다. 결과 미확인 기록은 반드시 살아남아야 한다.
 *
 * <p><b>다만 트랜잭션을 걸지 않는 것만으로는 부족하다.</b> 보상 경로에서는 이 클래스가
 * {@code CompensationExecutor.perform}의 트랜잭션 <i>안에서</i> 불리므로, 그냥 저장하면
 * 호출자의 커밋 경계에 묶인다. 그래서 상태 기록은 {@link PaymentRecorder}에 맡겨
 * 호출 맥락과 무관하게 즉시 커밋되게 한다.
 *
 * <p>그 대가로 이 클래스는 <b>읽어온 Payment를 절대 변경하지 않는다.</b> 호출자의 트랜잭션
 * 안에서 변경하면 그 행에 X락이 걸리고, 뒤이은 기록기의 새 트랜잭션이 보류된 자기 호출자의
 * 락을 기다리다 멈춘다. 전이는 전부 기록기 쪽 사본에서 일어난다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentRecorder paymentRecorder;
    private final PaymentGateway paymentGateway;
    private final ProcessingProperties properties;
    private final ExecutorService paymentExecutor;

    /**
     * 결제를 요청한다. (FR-06)
     *
     * <p>요청 사실을 먼저 커밋한 뒤 대행을 부른다. 순서가 반대면 타임아웃이 났을 때
     * 승인됐을지도 모르는 결제가 기록 없이 사라진다.
     *
     * <p>이미 결과가 확정된 결제라면 대행을 다시 부르지 않고 그 결과를 그대로 돌려준다.
     * 재처리로 같은 주문이 두 번 들어와도 이중 승인이 생기지 않는다.
     */
    public PaymentOutcome pay(String orderNo, long amount) {
        Payment payment = paymentRecorder.requested(orderNo, amount);

        if (payment.getStatus().isTerminal()) {
            log.info("이미 결과가 확정된 결제다. 대행을 다시 부르지 않는다: {} ({})",
                    orderNo, payment.getStatus());
            return outcomeOf(payment.getStatus());
        }

        try {
            GatewayStatus status = withinBudget(orderNo,
                    () -> paymentGateway.approve(idempotencyKey(orderNo), amount));
            return applyApproval(orderNo, status);
        } catch (TimeoutException e) {
            // 거절이 아니다. 결과를 모를 뿐이며 Payment는 UNKNOWN으로 남는다.
            log.warn("결제 결과 미확인: {} (시간 예산 {})", orderNo, properties.paymentTimeout());
            return PaymentOutcome.TIMEOUT;
        } catch (RejectedExecutionException e) {
            // 대기열이 가득 차 요청을 보내지 못했다. 보내지 않은 것이 확실하므로
            // 미확인이 아니라 확정된 실패다. 승인이 뒤늦게 생길 여지가 없다.
            log.error("결제 요청을 보내지 못했다. 대기열 포화: {}", orderNo);
            paymentRecorder.record(orderNo, Payment::decline);
            return PaymentOutcome.DECLINED;
        }
    }

    /**
     * 결제 대행에 실제 결과를 되묻고 우리 기록을 맞춘다. 타임아웃 이후의 첫 조치다.
     *
     * <p>대행에 승인 이력이 없다고 나와도 <b>거절로 단정하지 않는다.</b> 우리가 보낸
     * 요청이 아직 처리 중일 수 있기 때문이다. 이 경우 UNRESOLVED를 돌려주고 판단을 미룬다.
     *
     * <p>조회에도 승인과 같은 시간 예산을 건다. 이 메서드는 보상 경로에서 불리고, 보상은
     * 단일 스레드 스케줄러가 돌린다. 예산이 없으면 대행 한 번의 무응답으로 이후 모든
     * 정산 주기가 실행되지 않는다. (NFR-03, NFR-05)
     */
    public PaymentVerification verify(String orderNo) {
        Optional<Payment> found = paymentRepository.findByOrderNo(orderNo);
        if (found.isEmpty()) {
            // 결제를 요청한 적이 없다. 되돌릴 것도 없다.
            return PaymentVerification.NOT_APPROVED;
        }

        Payment payment = found.get();
        if (payment.getStatus().isTerminal()) {
            return payment.getStatus() == PaymentStatus.APPROVED
                    ? PaymentVerification.APPROVED
                    : PaymentVerification.NOT_APPROVED;
        }

        GatewayStatus status;
        try {
            status = withinBudget(orderNo, () -> paymentGateway.inquire(idempotencyKey(orderNo)));
        } catch (TimeoutException | RejectedExecutionException e) {
            // 못 물어봤다는 것도 '아직 모른다'다. 거절로 단정하면 뒤늦은 승인이 취소되지 않는다.
            log.warn("결제 조회가 시간 예산 안에 끝나지 않았다. 미확인으로 남긴다: {} (예산 {})",
                    orderNo, properties.paymentTimeout());
            return PaymentVerification.UNRESOLVED;
        }

        return switch (status) {
            case APPROVED -> {
                paymentRecorder.record(orderNo, Payment::approve);
                yield PaymentVerification.APPROVED;
            }
            case DECLINED -> {
                paymentRecorder.record(orderNo, Payment::decline);
                yield PaymentVerification.NOT_APPROVED;
            }
            // 대행이 이미 취소했다. 승인이 있었다는 사실도 함께 남긴다.
            case CANCELLED -> {
                paymentRecorder.record(orderNo, Payment::confirmCancelled);
                yield PaymentVerification.NOT_APPROVED;
            }
            // 승인 이력이 없다 == 아직 모른다. UNKNOWN을 유지하고 다음 주기에 다시 묻는다.
            case NOT_FOUND -> {
                log.info("결제 결과를 아직 확인하지 못했다. 미확인으로 남긴다: {}", orderNo);
                yield PaymentVerification.UNRESOLVED;
            }
        };
    }

    /**
     * 승인된 결제를 되돌린다. (FR-07)
     *
     * <p>조회로 결과를 확정한 뒤에만 취소한다. 아직 확인되지 않았다면 예외를 던져
     * 보상 작업이 미결로 남게 한다. 정산이 다음 주기에 다시 시도한다. (§8 R-5)
     *
     * @throws PaymentTimeoutException 결과를 확인하지 못해 취소 여부를 정할 수 없을 때
     */
    public void cancel(String orderNo) {
        PaymentVerification verification = verify(orderNo);
        if (verification == PaymentVerification.UNRESOLVED) {
            throw new PaymentTimeoutException(orderNo);
        }

        if (verification == PaymentVerification.APPROVED) {
            cancelWithinBudget(orderNo);
        }
        // 결제를 요청한 적이 없으면 되돌릴 것도 없다. 기록기가 없는 건을 조용히 넘어간다.
        paymentRecorder.record(orderNo, Payment::cancel);
    }

    /**
     * 취소를 시간 예산 안에서만 기다린다.
     *
     * <p>예산을 넘기면 취소가 반영됐는지 알 수 없다. 그 상태로 우리 기록만 CANCELLED로 닫으면,
     * 실제로는 승인된 채 남은 대금을 아무도 되돌리지 않는다. 그래서 예외를 던져 보상 작업을
     * 미결로 남긴다. 멱등키가 재시도 사이에 바뀌지 않으므로 다음 주기의 재시도가
     * 이중 취소를 만들지 않는다. (§8 R-5)
     *
     * @throws PaymentTimeoutException 취소 결과를 확인하지 못했을 때
     */
    private void cancelWithinBudget(String orderNo) {
        try {
            withinBudget(orderNo, () -> paymentGateway.cancel(cancelIdempotencyKey(orderNo)));
        } catch (TimeoutException | RejectedExecutionException e) {
            log.warn("결제 취소가 시간 예산 안에 끝나지 않았다. 보상을 미결로 남긴다: {} (예산 {})",
                    orderNo, properties.paymentTimeout());
            throw new PaymentTimeoutException(orderNo);
        }
    }

    /**
     * 승인 요청의 응답을 우리 기록에 반영한다. 이 경로에서는 NOT_FOUND가 나오지 않는다.
     */
    private PaymentOutcome applyApproval(String orderNo, GatewayStatus status) {
        if (status == GatewayStatus.APPROVED) {
            paymentRecorder.record(orderNo, Payment::approve);
            return PaymentOutcome.APPROVED;
        }
        paymentRecorder.record(orderNo, Payment::decline);
        return PaymentOutcome.DECLINED;
    }

    private PaymentOutcome outcomeOf(PaymentStatus status) {
        return status == PaymentStatus.APPROVED ? PaymentOutcome.APPROVED : PaymentOutcome.DECLINED;
    }

    /**
     * 시간 예산 안에서만 기다린다. 예산을 넘겨도 대행 쪽 처리는 계속될 수 있으며,
     * 그래서 우리는 "실패"가 아니라 "미확인"으로 판정한다. (NFR-05)
     *
     * <p>전용 실행자를 쓴다. 공용 ForkJoinPool에서 돌리면 타임아웃된 호출이 스레드를
     * 계속 점유해, 뒤이은 결제가 실제 지연과 무관하게 타임아웃 판정을 받는다.
     */
    private GatewayStatus withinBudget(String orderNo, Supplier<GatewayStatus> call)
            throws TimeoutException {
        try {
            return CompletableFuture.supplyAsync(call, paymentExecutor)
                    .get(properties.paymentTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | RejectedExecutionException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TimeoutException(orderNo);
        } catch (Exception e) {
            throw new IllegalStateException("결제 요청이 실패했다: " + orderNo, e);
        }
    }

    /**
     * 멱등키는 주문번호에서 결정적으로 파생한다. 재시도 사이에 절대 바뀌지 않는다.
     */
    private String idempotencyKey(String orderNo) {
        return orderNo;
    }

    private String cancelIdempotencyKey(String orderNo) {
        return orderNo + ":cancel";
    }
}
