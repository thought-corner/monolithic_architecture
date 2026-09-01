package com.study.monolithic_architecture.controller.dto;

import com.study.monolithic_architecture.constants.OrderStatus;
import com.study.monolithic_architecture.service.dto.OrderAcceptResult;

/**
 * 주문 접수 응답. 주문번호와 접수됨 상태를 즉시 돌려준다. (FR-03)
 * 재고·결제 처리 완료를 기다리지 않는다. (NFR-01)
 */
public record OrderAcceptResponse(String orderNo, OrderStatus status) {

	public static OrderAcceptResponse from(OrderAcceptResult result) {
		return new OrderAcceptResponse(result.orderNo(), result.status());
	}
}
