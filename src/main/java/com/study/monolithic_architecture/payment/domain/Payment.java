package com.study.monolithic_architecture.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제. 한 주문에 대한 한 건의 대금 처리 기록.
 *
 * <p>요청을 보낸 시점의 상태는 UNKNOWN이다. 타임아웃이 나도 실패로 단정하지 않고,
 * 조회로 실제 결과를 확인한 뒤에만 취소한다.
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String orderNo;

	@Column(nullable = false)
	private long amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private PaymentStatus status;

	/** 결제를 요청한다. 응답을 받기 전이므로 결과는 미확인이다. */
	public Payment(String orderNo, long amount) {
		this.orderNo = orderNo;
		this.amount = amount;
		this.status = PaymentStatus.UNKNOWN;
	}

	/**
	 * 조회 또는 응답으로 승인이 확인됐다.
	 *
	 * <p><b>같은 결과를 다시 확인하는 것은 오류가 아니다.</b> 우리 기록을 갱신하는 경로는
	 * 둘이고(요청 응답 반영, 정산의 조회 해소) 둘 다 같은 대행 원장을 읽으므로, 같은 결론에
	 * 두 번 도달하는 일이 정상적으로 일어난다. 이것을 예외로 만들면 주문 처리가 중단되고
	 * 승인된 결제가 뒤늦게 취소 대상이 된다.
	 *
	 * <p>가드가 막아야 하는 것은 확정된 결과를 <b>다른 결과로</b> 뒤집는 일뿐이다.
	 */
	public void approve() {
		if (status == PaymentStatus.APPROVED) {
			return;
		}
		requireUnknown();
		this.status = PaymentStatus.APPROVED;
	}

	/** 조회 또는 응답으로 거절이 확인됐다. 승인과 마찬가지로 재확인은 오류가 아니다. */
	public void decline() {
		if (status == PaymentStatus.DECLINED) {
			return;
		}
		requireUnknown();
		this.status = PaymentStatus.DECLINED;
	}

	/**
	 * 승인된 결제를 되돌린다. (FR-07)
	 *
	 * <p>이미 취소됐거나 애초에 승인된 적이 없으면 되돌릴 것이 없으므로 성공으로 본다.
	 * 결과가 미확인인 상태에서는 취소하지 않는다. 조회가 먼저다.
	 */
	public void cancel() {
		if (status == PaymentStatus.UNKNOWN) {
			throw new IllegalStateException(
				"결과가 미확인인 결제는 조회 후에만 취소할 수 있다: " + orderNo);
		}
		if (status == PaymentStatus.APPROVED) {
			this.status = PaymentStatus.CANCELLED;
		}
		// CANCELLED: 이미 되돌아갔다. DECLINED: 되돌릴 승인이 없다. 둘 다 성공이다.
	}

	/**
	 * 대행이 이미 취소했음을 확인했다. 승인이 있었다는 사실과 취소를 함께 남긴다.
	 *
	 * <p>승인 없이 곧바로 취소로 적기 위한 우회로가 아니다. 대행이 CANCELLED를 돌려준 것은
	 * 승인이 있었다는 뜻이므로 두 사건을 순서대로 남긴다. 재확인은 오류가 아니다.
	 */
	public void confirmCancelled() {
		if (status == PaymentStatus.CANCELLED) {
			return;
		}
		approve();
		cancel();
	}

	private void requireUnknown() {
		if (status != PaymentStatus.UNKNOWN) {
			throw new IllegalStateException(
				"이미 결과가 확정된 결제다: %s (%s)".formatted(orderNo, status));
		}
	}
}
