package com.study.monolithic_architecture.controller.dto;

import com.study.monolithic_architecture.service.dto.OrderAcceptCommand;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 주문 접수 요청 본문. (FR-03)
 *
 * <p>FR-04: 없는 상품이거나 수량이 범위 밖이면 접수 자체가 거절된다.
 * 수량 제약은 여기서 400으로 거르고, 통과하더라도 도메인 생성자가 다시 막는다.
 * 표현 계층을 우회해도 BR-1이 깨지지 않게 하기 위해서다.
 *
 * @param requestId 클라이언트가 만드는 요청식별자. 같은 값이면 주문을 새로 만들지 않는다. (NFR-02)
 */
public record OrderAcceptRequest(

	@NotBlank(message = "요청식별자는 필수다")
	String requestId,

	@NotNull(message = "상품ID는 필수다")
	Long productId,

	@Min(value = 1, message = "주문 수량은 1개 이상이어야 한다")
	@Max(value = 10, message = "주문 수량은 10개 이하여야 한다")
	int quantity) {

	public OrderAcceptCommand toCommand() {
		return new OrderAcceptCommand(requestId, productId, quantity);
	}
}
