package com.study.monolithic_architecture.order.controller.dto;

import com.study.monolithic_architecture.order.domain.FailureReason;
import com.study.monolithic_architecture.order.domain.OrderStatus;
import com.study.monolithic_architecture.order.service.dto.OrderInfo;
import java.time.LocalDateTime;

/**
 * 주문 조회 응답. (FR-08, FR-09)
 *
 * <p>응답 형태가 바뀌어도 서비스는 그대로다. 그 반대도 마찬가지다.
 */
public record OrderResponse(String orderNo,
							OrderStatus status,
							long orderAmount,
							Long productId,
							String productName,
							int quantity,
							FailureReason failureReason,
							LocalDateTime acceptedAt,
							LocalDateTime settledAt) {

	public static OrderResponse from(OrderInfo info) {
		return new OrderResponse(
			info.orderNo(), info.status(), info.orderAmount(),
			info.productId(), info.productName(), info.quantity(),
			info.failureReason(), info.acceptedAt(), info.settledAt());
	}
}
