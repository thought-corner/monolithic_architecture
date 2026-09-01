package com.study.monolithic_architecture.payment.domain;

/**
 * 결제 대행이 시간 예산 안에 응답하지 않았다. (NFR-05)
 *
 * <p>이것은 "거절됐다"가 아니라 "결과를 모른다"는 뜻이다.
 * 승인이 실제로 이뤄졌을 수 있으므로 반드시 조회로 확인한 뒤 취소해야 한다.
 */
public class PaymentTimeoutException extends RuntimeException {

	public PaymentTimeoutException(String orderNo) {
		super("결제 결과를 확인하지 못했다: " + orderNo);
	}
}
