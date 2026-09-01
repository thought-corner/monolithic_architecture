package com.study.monolithic_architecture.order.service.dto;

import com.study.monolithic_architecture.order.domain.OrderStatus;
import com.study.monolithic_architecture.order.domain.OrderStatusHistory;
import java.time.LocalDateTime;

/**
 * 주문 상태 변경 이력 한 줄. (FR-10)
 */
public record OrderHistoryInfo(OrderStatus fromStatus,
							   OrderStatus toStatus,
							   String reason,
							   LocalDateTime occurredAt) {

	public static OrderHistoryInfo from(OrderStatusHistory history) {
		return new OrderHistoryInfo(
			history.getFromStatus(), history.getToStatus(),
			history.getReason(), history.getOccurredAt());
	}
}
