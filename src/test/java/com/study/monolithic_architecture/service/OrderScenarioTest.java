package com.study.monolithic_architecture.service;

import com.study.monolithic_architecture.constants.FailureReason;
import com.study.monolithic_architecture.constants.OrderStatus;
import com.study.monolithic_architecture.domain.Product;
import com.study.monolithic_architecture.service.dto.OrderAcceptCommand;
import com.study.monolithic_architecture.service.dto.OrderAcceptResult;
import com.study.monolithic_architecture.service.dto.OrderHistoryInfo;
import com.study.monolithic_architecture.service.dto.OrderInfo;
import com.study.monolithic_architecture.repository.PaymentRepository;
import com.study.monolithic_architecture.repository.ProductRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.study.monolithic_architecture.TestcontainersConfiguration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * PRD의 시나리오 S1·S2·S3. 구조를 바꿔도 이 셋의 관찰 가능한 결과는 같아야 한다.
 *
 * <p>대기 조건은 경과 시간이 아니라 종결 상태 도달이다. 확인은 공개 조회로만 한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderScenarioTest {

	private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

	@Autowired
	OrderService orderService;
	@Autowired
	OrderHistoryService historyService;
	@Autowired
	ProductRepository productRepository;
	@Autowired
	PaymentRepository paymentRepository;

	@Test
	@DisplayName("S1 정상 주문: 확정되고 재고가 1 줄어든다")
	void s1_정상_주문() {
		Product product = productRepository.save(new Product("정상상품", 30_000L, 10));

		OrderAcceptResult accepted = orderService.accept(command(product.getId(), 1));
		assertThat(accepted.status()).isEqualTo(OrderStatus.ACCEPTED);

		OrderInfo settled = awaitSettled(accepted.orderNo());

		assertThat(settled.status()).isEqualTo(OrderStatus.CONFIRMED);
		assertThat(settled.orderAmount()).isEqualTo(30_000L);
		assertThat(reload(product).getStockQuantity()).isEqualTo(9);
		assertThat(reload(product).getReservedQuantity()).isZero();
	}

	@Test
	@DisplayName("S2 재고 부족: 실패하고 재고는 그대로이며 결제 기록이 없다")
	void s2_재고_부족() {
		Product product = productRepository.save(new Product("품절상품", 30_000L, 1));

		OrderAcceptResult accepted = orderService.accept(command(product.getId(), 5));
		OrderInfo settled = awaitSettled(accepted.orderNo());

		assertThat(settled.status()).isEqualTo(OrderStatus.FAILED);
		assertThat(settled.failureReason()).isEqualTo(FailureReason.OUT_OF_STOCK);
		assertThat(reload(product).getStockQuantity()).isEqualTo(1);
		assertThat(paymentRepository.findByOrderNo(accepted.orderNo())).isEmpty();
	}

	@Test
	@DisplayName("S3 결제 거절: 원복되고 이력에 확보와 해제가 모두 남는다")
	void s3_결제_거절과_원복() {
		Product product = productRepository.save(new Product("고가상품", 150_000L, 10));

		OrderAcceptResult accepted = orderService.accept(command(product.getId(), 1));
		OrderInfo settled = awaitSettled(accepted.orderNo());

		assertThat(settled.status()).isEqualTo(OrderStatus.FAILED);
		assertThat(settled.failureReason()).isEqualTo(FailureReason.PAYMENT_DECLINED);
		assertThat(reload(product).getStockQuantity()).isEqualTo(10);
		assertThat(reload(product).getReservedQuantity()).isZero();

		List<String> reasons = historyService.findByOrderNo(accepted.orderNo()).stream()
			.map(OrderHistoryInfo::reason)
			.toList();
		assertThat(reasons).contains("재고 확보", "재고 확보 해제");
	}

	@Test
	@DisplayName("NFR-02 멱등: 같은 요청식별자로 10회 호출해도 주문은 한 건이다")
	void nfr02_멱등성() {
		Product product = productRepository.save(new Product("멱등상품", 30_000L, 10));
		String requestId = newRequestId();

		List<String> orderNos = java.util.stream.IntStream.range(0, 10)
			.mapToObj(i -> orderService.accept(new OrderAcceptCommand(requestId, product.getId(), 1)).orderNo())
			.distinct()
			.toList();

		assertThat(orderNos).hasSize(1);
	}

	private OrderInfo awaitSettled(String orderNo) {
		await().atMost(AWAIT_TIMEOUT).pollInterval(POLL_INTERVAL)
			.untilAsserted(() -> assertThat(orderService.findByOrderNo(orderNo).status().isTerminal())
				.as("주문이 종결되지 않았다: %s", orderService.findByOrderNo(orderNo))
				.isTrue());
		return orderService.findByOrderNo(orderNo);
	}

	private Product reload(Product product) {
		return productRepository.findById(product.getId()).orElseThrow();
	}

	private OrderAcceptCommand command(Long productId, int quantity) {
		return new OrderAcceptCommand(newRequestId(), productId, quantity);
	}

	private String newRequestId() {
		return "REQ-" + UUID.randomUUID();
	}
}
