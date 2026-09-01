package com.study.monolithic_architecture.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문. 구매자가 상품 하나를 수량 1~10으로 사겠다는 한 건의 요청. (BR-1)
 *
 * <p>상태는 BR-4에 따라 접수됨 → 확정됨 또는 접수됨 → 실패함으로만 전이하며,
 * 종결 상태에서는 되돌아가지 않는다. 원복이 끝나지 않은 주문은 ACCEPTED에 머문다. (BR-5)
 *
 * <p>setter를 두지 않는다. 상태 전이는 confirm()과 fail()로만 일어나야 하며,
 * setStatus()가 생기는 순간 BR-4를 지킬 방법이 사라진다.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

	public static final int MIN_QUANTITY = 1;
	public static final int MAX_QUANTITY = 10;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 외부에 노출되는 주문의 이름. 저장소 내부 키(id)와 섞어 쓰지 않는다. */
	@Column(nullable = false, unique = true)
	private String orderNo;

	/** 중복 요청 판별. unique 제약이 중복 주문 생성을 막는다. (NFR-02) */
	@Column(nullable = false, unique = true)
	private String requestId;

	@Column(nullable = false)
	private Long productId;

	@Min(MIN_QUANTITY)
	@Max(MAX_QUANTITY)
	@Column(nullable = false)
	private int quantity;

	@Column(nullable = false)
	private long orderAmount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderStatus status;

	@Enumerated(EnumType.STRING)
	private FailureReason failureReason;

	@Column(nullable = false)
	private LocalDateTime acceptedAt;

	private LocalDateTime settledAt;

	/**
	 * 두 트랜잭션이 같은 주문의 상태를 동시에 바꾸는 것을 막는다.
	 *
	 * <p>메모리 안의 requireAccepted() 가드만으로는 트랜잭션 경계를 넘는 경합을 막지 못한다.
	 * 정산이 실패로 닫는 사이 비동기 처리가 확정으로 닫으면, 각자 자기 스냅샷에서
	 * ACCEPTED를 보고 둘 다 통과한 뒤 나중 커밋이 앞선 결과를 덮어쓴다. (BR-4)
	 */
	@Version
	private Long version;

	/**
	 * 주문을 접수한다. 주문 금액은 여기서 확정된다. (BR-2)
	 */
	public Order(String orderNo, String requestId, Long productId,
		int quantity, long unitPrice, LocalDateTime acceptedAt) {
		if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
			throw new IllegalArgumentException(
				"주문 수량은 %d~%d 이어야 한다: %d".formatted(MIN_QUANTITY, MAX_QUANTITY, quantity));
		}
		this.orderNo = orderNo;
		this.requestId = requestId;
		this.productId = productId;
		this.quantity = quantity;
		this.orderAmount = unitPrice * quantity;
		this.status = OrderStatus.ACCEPTED;
		this.acceptedAt = acceptedAt;
	}

	/** 재고 차감과 결제 승인이 모두 끝났음을 종결한다. */
	public void confirm(LocalDateTime now) {
		requireAccepted();
		this.status = OrderStatus.CONFIRMED;
		this.settledAt = now;
	}

	/**
	 * 보상이 끝난 뒤 실패로 종결한다. (BR-5)
	 * 보상이 남아 있는 동안에는 이 메서드를 부르지 않는다.
	 */
	public void fail(FailureReason reason, LocalDateTime now) {
		requireAccepted();
		this.status = OrderStatus.FAILED;
		this.failureReason = reason;
		this.settledAt = now;
	}

	/**
	 * 종결 데드라인을 넘긴 접수 상태인가. 미결 보상을 식별하는 신호다.
	 */
	public boolean isStale(Duration settlementDeadline, LocalDateTime now) {
		return status == OrderStatus.ACCEPTED
			&& acceptedAt.plus(settlementDeadline).isBefore(now);
	}

	private void requireAccepted() {
		if (status != OrderStatus.ACCEPTED) {
			throw new IllegalStateException(
				"종결된 주문은 상태를 바꿀 수 없다: %s (%s)".formatted(orderNo, status));
		}
	}
}
