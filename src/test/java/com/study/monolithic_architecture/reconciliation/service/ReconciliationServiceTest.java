package com.study.monolithic_architecture.reconciliation.service;

import com.study.monolithic_architecture.TestcontainersConfiguration;
import com.study.monolithic_architecture.order.domain.FailureReason;
import com.study.monolithic_architecture.order.domain.Order;
import com.study.monolithic_architecture.order.domain.OrderStatus;
import com.study.monolithic_architecture.order.repository.OrderRepository;
import com.study.monolithic_architecture.payment.domain.Payment;
import com.study.monolithic_architecture.payment.domain.PaymentStatus;
import com.study.monolithic_architecture.payment.repository.PaymentRepository;
import com.study.monolithic_architecture.product.domain.Product;
import com.study.monolithic_architecture.product.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정산이 실제로 무엇을 하는지 확인한다. (NFR-03)
 *
 * <p>스케줄러를 트리거와 절차로 나눈 덕에 주기(1분)를 기다리지 않고 절차를 직접 부른다.
 * 나누기 전에는 이 검증 자체가 불가능했다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReconciliationServiceTest {

	@Autowired
	ReconciliationService reconciliationService;
	@Autowired
	OrderRepository orderRepository;
	@Autowired
	ProductRepository productRepository;
	@Autowired
	PaymentRepository paymentRepository;

	/** 다른 시험이 남긴 행과 섞이지 않도록 넉넉히 가져와 내 것만 추린다. */
	private static final int LOOKAHEAD = 1_000;

	@Test
	@DisplayName("종결 데드라인을 넘긴 접수 주문을 실패로 종결한다")
	void 지연된_주문을_종결한다() {
		Product product = productRepository.save(new Product("정산상품", 30_000L, 10));
		Order stale = orderRepository.save(newOrder(product, LocalDateTime.now().minusHours(1)));

		reconciliationService.settleCompensatedOrders();

		Order reloaded = orderRepository.findByOrderNo(stale.getOrderNo()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.FAILED);
		assertThat(reloaded.getFailureReason()).isEqualTo(FailureReason.TIMEOUT);
		assertThat(reloaded.getSettledAt()).isNotNull();
	}

	@Test
	@DisplayName("데드라인 안쪽의 접수 주문은 건드리지 않는다")
	void 아직_이른_주문은_두고_본다() {
		Product product = productRepository.save(new Product("대기상품", 30_000L, 10));
		Order fresh = orderRepository.save(newOrder(product, LocalDateTime.now()));

		reconciliationService.settleCompensatedOrders();

		Order reloaded = orderRepository.findByOrderNo(fresh.getOrderNo()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
	}

	@Test
	@DisplayName("reconcile()은 세 단계를 모두 돌린다")
	void 전체_정산이_예외_없이_돈다() {
		reconciliationService.reconcile();
	}

	@Test
	@DisplayName("미확인 결제는 등록 순서로 훑는다")
	void 미확인_결제는_등록_순서로_훑는다() {
		List<String> saved = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			saved.add(paymentRepository.save(new Payment("ORD-SEQ-" + UUID.randomUUID(), 30_000L))
				.getOrderNo());
		}

		List<String> mine = paymentRepository
			.findAllByStatusOrderByIdAsc(PaymentStatus.UNKNOWN, PageRequest.of(0, LOOKAHEAD)).stream()
			.map(Payment::getOrderNo)
			.filter(saved::contains)
			.toList();

		assertThat(mine)
			.as("배치 상한 아래에서 어떤 건이 뽑힐지 DB에 맡기면, 뒤에 등록된 결제가 앞을 차지해 "
				+ "오래된 미확인 결제가 계속 밀릴 수 있다")
			.containsExactlyElementsOf(saved);
	}

	@Test
	@DisplayName("지연된 주문은 접수 순서로 훑는다")
	void 지연된_주문은_접수_순서로_훑는다() {
		Product product = productRepository.save(new Product("순서상품", 30_000L, 10));
		List<String> saved = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			saved.add(orderRepository.save(newOrder(product, LocalDateTime.now().minusHours(1)))
				.getOrderNo());
		}

		List<String> mine = orderRepository.findAllByStatusAndAcceptedAtBeforeOrderByIdAsc(
				OrderStatus.ACCEPTED, LocalDateTime.now(), PageRequest.of(0, LOOKAHEAD)).stream()
			.map(Order::getOrderNo)
			.filter(saved::contains)
			.toList();

		assertThat(mine).containsExactlyElementsOf(saved);
	}

	private Order newOrder(Product product, LocalDateTime acceptedAt) {
		String suffix = UUID.randomUUID().toString();
		return new Order("ORD-" + suffix, "REQ-" + suffix,
			product.getId(), 1, product.getPrice(), acceptedAt);
	}
}
