package com.study.monolithic_architecture.controller.dto;

import com.study.monolithic_architecture.constants.OrderStatus;
import com.study.monolithic_architecture.service.dto.OrderHistoryInfo;

import java.time.LocalDateTime;

/**
 * 주문 상태 변경 이력 응답. (FR-10)
 */
public record OrderHistoryResponse(OrderStatus fromStatus,
								   OrderStatus toStatus,
								   String reason,
								   LocalDateTime occurredAt) {

	public static OrderHistoryResponse from(OrderHistoryInfo info) {
		return new OrderHistoryResponse(
			info.fromStatus(), info.toStatus(), info.reason(), info.occurredAt());
	}
}
