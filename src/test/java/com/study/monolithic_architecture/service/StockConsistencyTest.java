package com.study.monolithic_architecture.service;

import com.study.monolithic_architecture.TestcontainersConfiguration;
import com.study.monolithic_architecture.constants.OrderStatus;
import com.study.monolithic_architecture.domain.Order;
import com.study.monolithic_architecture.domain.Product;
import com.study.monolithic_architecture.repository.OrderRepository;
import com.study.monolithic_architecture.repository.ProductRepository;
import com.study.monolithic_architecture.service.dto.OrderAcceptCommand;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * BR-7: 어떤 시점에도 {@code 총재고 + 확정된 주문의 수량 합 = 초기 재고}.
 *
 * <p>도메인 단위 시험(StockInvariantTest)이 규칙 자체를 확인했다면, 여기서는
 * 실제 저장소와 동시 요청 아래서도 그 규칙이 유지되는지 본다.
 * 초과 판매가 일어나면 이 시험이 잡는다.
 *
 * <p>주의: 불변식은 <b>주문이 종결되지 않아도</b> 성립해야 한다.
 * 확보만 된 주문은 확정 수량 합에 들어가지 않고 총재고도 줄이지 않기 때문이다.
 * 그래서 종결을 기다리되, 기다림이 끝나지 않아도 불변식은 검사한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StockConsistencyTest {

	private static final Sort ANY = Sort.by(Sort.Direction.DESC, "acceptedAt");

	@Autowired
	OrderService orderService;
	@Autowired
	OrderRepository orderRepository;
	@Autowired
	ProductRepository productRepository;

	@Test
	@DisplayName("가용재고보다 많은 주문이 동시에 들어와도 재고 정합성이 유지된다")
	void 동시_주문에도_정합성이_유지된다() throws Exception {
		int initialStock = 5;
		int concurrentOrders = 20;
		Product product = productRepository.save(new Product("동시상품", 30_000L, initialStock));

		fireConcurrently(product.getId(), concurrentOrders);
		awaitQuiescence(product.getId());

		int stock = reload(product).getStockQuantity();
		int confirmed = confirmedQuantity(product.getId());

		assertThat(stock + confirmed)
			.as("총재고(%d) + 확정 수량 합(%d) 은 초기 재고(%d) 와 같아야 한다 — BR-7",
				stock, confirmed, initialStock)
			.isEqualTo(initialStock);
	}

	@Test
	@DisplayName("초과 판매가 일어나지 않는다. 확정 수량은 초기 재고를 넘지 못한다")
	void 초과_판매가_없다() throws Exception {
		int initialStock = 3;
		Product product = productRepository.save(new Product("한정상품", 30_000L, initialStock));

		fireConcurrently(product.getId(), 15);
		awaitQuiescence(product.getId());

		assertThat(confirmedQuantity(product.getId())).isLessThanOrEqualTo(initialStock);
		assertThat(reload(product).getStockQuantity()).isNotNegative();
		assertThat(reload(product).getAvailableQuantity()).isNotNegative();
	}

	@Test
	@DisplayName("락 충돌이 나도 모든 주문이 종결된다. 접수 상태로 방치되지 않는다")
	void 주문이_방치되지_않는다() throws Exception {
		Product product = productRepository.save(new Product("경합상품", 30_000L, 10));

		fireConcurrently(product.getId(), 20);
		awaitQuiescence(product.getId());

		List<Order> stranded = ordersOf(product.getId()).stream()
			.filter(order -> order.getStatus() == OrderStatus.ACCEPTED)
			.toList();

		assertThat(stranded)
			.as("낙관적 락 충돌은 재시도해야 할 실패다. 방치되면 정산이 TIMEOUT으로 잘못 종결한다")
			.isEmpty();
	}

	/** 동시에 접수시킨다. 모든 스레드가 같은 순간 출발하도록 래치로 맞춘다. */
	private void fireConcurrently(Long productId, int count) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(Math.min(count, 8));
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(count);
		try {
			for (int i = 0; i < count; i++) {
				pool.submit(() -> {
					try {
						start.await();
						orderService.accept(new OrderAcceptCommand("REQ-" + UUID.randomUUID(), productId, 1));
					} catch (Exception ignored) {
						// 접수 실패도 정상 결과다. 불변식은 그래도 성립해야 한다.
					} finally {
						done.countDown();
					}
				});
			}
			start.countDown();
			assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
		} finally {
			pool.shutdown();
		}
	}

	/**
	 * 처리가 잦아들기를 기다린다. 모두 종결되지 않아도 실패시키지 않는다.
	 * BR-7은 종결 여부와 무관하게 성립해야 하는 규칙이기 때문이다.
	 */
	private void awaitQuiescence(Long productId) {
		try {
			await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
				.until(() -> ordersOf(productId).stream()
					.noneMatch(order -> order.getStatus() == OrderStatus.ACCEPTED));
		} catch (ConditionTimeoutException e) {
			// 일부가 접수 상태로 남았다. 불변식 검사는 그대로 진행한다.
		}
	}

	private int confirmedQuantity(Long productId) {
		return ordersOf(productId).stream()
			.filter(order -> order.getStatus() == OrderStatus.CONFIRMED)
			.mapToInt(Order::getQuantity)
			.sum();
	}

	private List<Order> ordersOf(Long productId) {
		return orderRepository.findAll(ANY).stream()
			.filter(order -> productId.equals(order.getProductId()))
			.toList();
	}

	private Product reload(Product product) {
		return productRepository.findById(product.getId()).orElseThrow();
	}
}
