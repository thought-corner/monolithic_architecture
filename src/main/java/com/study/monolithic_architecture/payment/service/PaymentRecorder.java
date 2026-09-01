package com.study.monolithic_architecture.payment.service;

import com.study.monolithic_architecture.compensation.service.CompensationExecutor;
import com.study.monolithic_architecture.payment.domain.Payment;
import com.study.monolithic_architecture.payment.repository.PaymentRepository;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 상태 변경을 <b>자체 트랜잭션</b>으로 커밋한다.
 *
 * <p>대행이 알려준 결과는 우리가 관찰한 사실이며, 그것을 알아낸 작업이 나중에 실패해도
 * 사라지면 안 된다. 보상은 되돌리기와 완료 표시를 한 트랜잭션에 묶으므로, 결제 기록이
 * 그 트랜잭션에 합류하면 뒤이은 취소 실패가 <b>방금 확정한 승인 사실까지</b> 되돌린다.
 * 그러면 다음 주기가 같은 조회를 다시 보내야 하고, 그 사이 재시도 상한만 소모된다.
 *
 * <p>{@code REQUIRES_NEW}는 호출자의 트랜잭션을 잠시 밀어두고 별도로 커밋한다.
 * 같은 클래스 안에서 부르면 프록시를 우회해 전파 설정이 걸리지 않으므로 빈을 나눴다.
 * {@link CompensationExecutor}를 분리한 것과 같은 이유다.
 *
 * <p><b>엔티티가 아니라 주문번호를 받는다.</b> 이것이 이 클래스에서 가장 중요한 제약이다.
 * 호출자의 트랜잭션이 이미 그 행을 갱신했다면 X락을 쥐고 있는데, 보류된 트랜잭션은
 * 이 트랜잭션이 끝나야 재개된다. 같은 행을 여기서 다시 갱신하면 <b>자기가 쥔 락을
 * 자기가 기다리는</b> 교착이 된다. 두 트랜잭션이 서로를 기다리는 형태가 아니라
 * InnoDB가 감지하지도 못하고 잠금 대기 시간이 다 찰 때까지 멈춘다.
 * 그래서 여기서 <b>직접 읽어</b> 이 트랜잭션만의 사본을 다루고, 호출자는 자기 사본을
 * 더럽히지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PaymentRecorder {

    private final PaymentRepository paymentRepository;

    /**
     * 결제 요청 사실을 지금 커밋한다. 없으면 만들고, 있으면 그대로 돌려준다.
     *
     * <p>대행을 부르기 <b>전에</b> 커밋해야 한다. 순서가 반대면 타임아웃이 났을 때
     * 승인됐을지도 모르는 결제가 기록 없이 사라진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment requested(String orderNo, long amount) {
        return paymentRepository.findByOrderNo(orderNo)
                .orElseGet(() -> paymentRepository.save(new Payment(orderNo, amount)));
    }

    /**
     * 확인된 결과를 지금 커밋한다. 호출자가 뒤에 실패해도 이 기록은 남는다.
     *
     * <p>전이는 {@link Payment}의 메서드로만 일어난다. 여기서 상태를 직접 대입하면
     * "미확인 결제는 조회 후에만 취소한다" 같은 규칙을 우회하게 된다.
     *
     * @param transition 이 트랜잭션이 읽어온 사본에 적용할 전이
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String orderNo, Consumer<Payment> transition) {
        paymentRepository.findByOrderNo(orderNo).ifPresent(transition);
    }
}
