package com.study.monolithic_architecture.service.dto;

import com.study.monolithic_architecture.constants.OrderStatus;
import com.study.monolithic_architecture.domain.Order;

/**
 * 주문 접수 결과. 주문번호와 접수됨 상태. (FR-03)
 */
public record OrderAcceptResult(String orderNo, OrderStatus status) {

	public static OrderAcceptResult from(Order order) {
		return new OrderAcceptResult(order.getOrderNo(), order.getStatus());
	}
}
