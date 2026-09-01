package com.study.monolithic_architecture.service.compensation;

import com.study.monolithic_architecture.constants.CompensationType;
import com.study.monolithic_architecture.service.OrderHistoryService;
import com.study.monolithic_architecture.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 승인된 결제를 되돌린다. (FR-07)
 *
 * <p>취소를 곧바로 보내지 않는다. PaymentService가 먼저 조회로 사실을 확인하고,
 * 승인이 확인된 경우에만 취소가 나간다. (§8 R-1)
 */
@Component
@RequiredArgsConstructor
public class CancelPaymentHandler implements CompensationHandler {

    private final PaymentService paymentService;
    private final OrderHistoryService historyService;

    @Override
    public CompensationType type() {
        return CompensationType.CANCEL_PAYMENT;
    }

    @Override
    public void compensate(String orderNo) {
        paymentService.cancel(orderNo);
        historyService.recordWithinAccepted(orderNo, "결제 승인 취소");
    }
}
