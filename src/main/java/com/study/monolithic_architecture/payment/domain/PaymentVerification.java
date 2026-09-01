package com.study.monolithic_architecture.payment.domain;

/**
 * 결제 조회로 알아낸 사실.
 *
 * <p>{@code UNRESOLVED}가 이 타입의 존재 이유다. "승인됐다"와 "승인되지 않았다"만으로는
 * <b>"아직 모른다"</b>를 표현할 수 없고, 모르는 것을 거절로 단정하면 이미 승인된 결제가
 * 취소되지 않은 채 남는다. (§8 R-1)
 */
public enum PaymentVerification {

	/** 승인이 확인됐다. 되돌리려면 취소가 필요하다. */
	APPROVED,

	/** 승인되지 않았음이 확인됐다. 되돌릴 것이 없다. */
	NOT_APPROVED,

	/**
	 * 아직 결과를 확인하지 못했다. 요청이 대행 쪽에서 진행 중일 수 있다.
	 * 이 값을 받으면 판단을 미루고 다음 정산 주기에 다시 조회해야 한다.
	 */
	UNRESOLVED
}
