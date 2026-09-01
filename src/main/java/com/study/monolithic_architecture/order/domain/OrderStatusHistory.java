package com.study.monolithic_architecture.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 상태 이력. 언제 어떤 상태로 왜 바뀌었는지 남긴다. (FR-10)
 */
@Entity
@Table(name = "order_status_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStatusHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String orderNo;

	/** 접수 시점에는 이전 상태가 없으므로 null이다. */
	@Enumerated(EnumType.STRING)
	private OrderStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OrderStatus toStatus;

	/** 전이 사유. 실패 사유보다 넓으며 성공 경로도 기록한다. */
	private String reason;

	@Column(nullable = false)
	private LocalDateTime occurredAt;

	public OrderStatusHistory(String orderNo, OrderStatus fromStatus, OrderStatus toStatus,
		String reason, LocalDateTime occurredAt) {
		this.orderNo = orderNo;
		this.fromStatus = fromStatus;
		this.toStatus = toStatus;
		this.reason = reason;
		this.occurredAt = occurredAt;
	}

	public static OrderStatusHistory accepted(String orderNo, String reason, LocalDateTime now) {
		return new OrderStatusHistory(orderNo, null, OrderStatus.ACCEPTED, reason, now);
	}
}
