package com.study.monolithic_architecture.service.dto;

import com.study.monolithic_architecture.constants.FailureReason;
import com.study.monolithic_architecture.constants.OrderStatus;
import com.study.monolithic_architecture.domain.Order;

import java.time.LocalDateTime;

/**
 * 주문 한 건의 조회 결과. 상태·금액·상품명·실패 사유. (FR-08, FR-09)
 *
 * <p>엔티티를 계층 밖으로 내보내지 않기 위한 경계다.
 * 상품명은 주문 엔티티에 없으므로 서비스가 합쳐서 채운다.
 */
public record OrderInfo(String orderNo,
						OrderStatus status,
						long orderAmount,
						Long productId,
						String productName,
						int quantity,
						FailureReason failureReason,
						LocalDateTime acceptedAt,
						LocalDateTime settledAt) {

	public static OrderInfo of(Order order, String productName) {
		return new OrderInfo(
			order.getOrderNo(), order.getStatus(), order.getOrderAmount(),
			order.getProductId(), productName, order.getQuantity(),
			order.getFailureReason(), order.getAcceptedAt(), order.getSettledAt());
	}
}
