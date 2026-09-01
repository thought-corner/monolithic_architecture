package com.study.monolithic_architecture.order.controller;

import com.study.monolithic_architecture.order.controller.dto.OrderAcceptRequest;
import com.study.monolithic_architecture.order.controller.dto.OrderAcceptResponse;
import com.study.monolithic_architecture.order.controller.dto.OrderHistoryResponse;
import com.study.monolithic_architecture.order.controller.dto.OrderResponse;
import com.study.monolithic_architecture.order.domain.OrderStatus;
import com.study.monolithic_architecture.order.service.OrderHistoryService;
import com.study.monolithic_architecture.order.service.OrderService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 주문 접수와 조회. (FR-03·04·08·09·10)
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

	private final OrderService orderService;
	private final OrderHistoryService historyService;

	/**
	 * FR-03: 주문을 접수한다.
	 *
	 * <p>202를 쓰는 이유는 받아들였을 뿐 아직 확정이 아니기 때문이다. 응답은 재고·결제
	 * 처리 완료를 기다리지 않는다. (NFR-01) 확정 여부는 FR-08로 확인한다.
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public OrderAcceptResponse accept(@Valid @RequestBody OrderAcceptRequest request) {
		return OrderAcceptResponse.from(orderService.accept(request.toCommand()));
	}

	/** FR-08: 상태·금액·상품명·실패 사유가 함께 반환된다. */
	@GetMapping("/{orderNo}")
	public OrderResponse findOne(@PathVariable String orderNo) {
		return OrderResponse.from(orderService.findByOrderNo(orderNo));
	}

	/** FR-09: 최신순. 상태로 필터 가능. */
	@GetMapping
	public List<OrderResponse> findAll(@RequestParam(required = false) OrderStatus status) {
		return orderService.findAll(status).stream()
			.map(OrderResponse::from)
			.toList();
	}

	/** FR-10: 언제 어떤 상태로 왜 바뀌었는지 조회한다. */
	@GetMapping("/{orderNo}/histories")
	public List<OrderHistoryResponse> findHistories(@PathVariable String orderNo) {
		return historyService.findByOrderNo(orderNo).stream()
			.map(OrderHistoryResponse::from)
			.toList();
	}
}
