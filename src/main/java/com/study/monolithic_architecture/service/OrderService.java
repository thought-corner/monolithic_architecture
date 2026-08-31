package com.study.monolithic_architecture.service;

import lombok.RequiredArgsConstructor;

import com.study.monolithic_architecture.constants.OrderStatus;
import com.study.monolithic_architecture.domain.Order;
import com.study.monolithic_architecture.domain.Product;
import com.study.monolithic_architecture.service.dto.OrderAcceptCommand;
import com.study.monolithic_architecture.service.dto.OrderAcceptResult;
import com.study.monolithic_architecture.service.dto.OrderInfo;
import com.study.monolithic_architecture.event.OrderAcceptedEvent;
import com.study.monolithic_architecture.exception.OrderNotFoundException;
import com.study.monolithic_architecture.exception.ProductNotFoundException;
import com.study.monolithic_architecture.repository.OrderRepository;
import com.study.monolithic_architecture.repository.ProductRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 주문 접수와 조회. (FR-03·04·08·09)
 *
 * <p>접수는 재고·결제 처리를 기다리지 않고 즉시 응답한다. (NFR-01)
 * 실제 처리는 커밋된 뒤 이벤트로 넘어간다.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

	/** FR-09의 최신순 기준. */
	private static final Sort LATEST_FIRST = Sort.by(Sort.Direction.DESC, "acceptedAt");

	private final OrderRepository orderRepository;
	private final ProductRepository productRepository;
	private final OrderHistoryService historyService;
	private final ApplicationEventPublisher eventPublisher;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	/**
	 * 주문을 접수한다. (FR-03)
	 *
	 * <p>같은 요청식별자로 다시 들어오면 새로 만들지 않고 기존 주문을 돌려준다. (NFR-02)
	 * 동시에 들어온 중복은 requestId의 unique 제약이 막고, 여기서 기존 건을 다시 읽는다.
	 */
	public OrderAcceptResult accept(OrderAcceptCommand command) {
		return findByRequestId(command.requestId())
			.orElseGet(() -> acceptNew(command));
	}

	/**
	 * 같은 요청식별자로 이미 접수된 주문. (NFR-02)
	 *
	 * <p>같은 빈 안에서 부르므로 프록시를 타지 않는다. @Transactional을 붙여도 걸리지
	 * 않으니 아예 두지 않는다. 조회 한 번이라 리포지토리 자체 트랜잭션으로 충분하다.
	 */
	private java.util.Optional<OrderAcceptResult> findByRequestId(String requestId) {
		return orderRepository.findByRequestId(requestId).map(OrderAcceptResult::from);
	}

	/**
	 * 주문을 새로 접수한다.
	 *
	 * <p>트랜잭션을 안쪽에서 직접 연다. 유니크 제약 위반을 같은 트랜잭션 안에서 잡으면
	 * 그 트랜잭션은 이미 롤백 전용이라 재조회도 커밋도 할 수 없다. 예외를 트랜잭션
	 * <b>밖에서</b> 받아야 새 트랜잭션으로 먼저 들어간 주문을 읽어올 수 있다. (NFR-02)
	 */
	private OrderAcceptResult acceptNew(OrderAcceptCommand command) {
		try {
			return transactionTemplate.execute(status -> register(command));
		} catch (DataIntegrityViolationException e) {
			// 같은 요청이 동시에 들어왔다. 먼저 들어간 주문이 정답이다.
			return findByRequestId(command.requestId()).orElseThrow(() -> e);
		}
	}

	private OrderAcceptResult register(OrderAcceptCommand command) {
		// FR-04: 없는 상품이거나 수량이 범위 밖이면 접수 자체가 거절된다.
		Product product = productRepository.findById(command.productId())
			.orElseThrow(() -> new ProductNotFoundException(command.productId()));

		Order order = new Order(generateOrderNo(), command.requestId(), command.productId(),
			command.quantity(), product.getPrice(), LocalDateTime.now(clock));
		orderRepository.saveAndFlush(order);

		historyService.recordAccepted(order.getOrderNo(), "주문 접수");

		// 커밋된 뒤에 처리가 시작된다. 접수 응답은 처리를 기다리지 않는다. (NFR-01)
		eventPublisher.publishEvent(new OrderAcceptedEvent(order.getOrderNo()));

		return OrderAcceptResult.from(order);
	}

	/** FR-08: 상태·금액·상품명·실패 사유를 함께 돌려준다. */
	@Transactional(readOnly = true)
	public OrderInfo findByOrderNo(String orderNo) {
		Order order = orderRepository.findByOrderNo(orderNo)
			.orElseThrow(() -> new OrderNotFoundException(orderNo));
		return OrderInfo.of(order, productName(order.getProductId()));
	}

	/** FR-09: 최신순 목록. 상태로 걸러낼 수 있다. */
	@Transactional(readOnly = true)
	public List<OrderInfo> findAll(OrderStatus status) {
		List<Order> orders = (status == null)
			? orderRepository.findAll(LATEST_FIRST)
			: orderRepository.findAllByStatus(status, LATEST_FIRST);
		return orders.stream()
			.map(order -> OrderInfo.of(order, productName(order.getProductId())))
			.toList();
	}

	private String productName(Long productId) {
		return productRepository.findById(productId).map(Product::getName).orElse(null);
	}

	private String generateOrderNo() {
		return "ORD-" + UUID.randomUUID();
	}
}
