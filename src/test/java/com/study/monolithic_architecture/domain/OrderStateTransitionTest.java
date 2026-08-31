package com.study.monolithic_architecture.domain;

import com.study.monolithic_architecture.constants.FailureReason;
import com.study.monolithic_architecture.constants.OrderStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BR-4: 주문은 접수됨 → 확정됨 또는 접수됨 → 실패함으로만 전이한다.
 * 확정·실패는 종결 상태이며 되돌아가지 않는다.
 *
 * <p>스프링을 띄우지 않는다. 이것은 도메인 규칙이고, 인프라와 무관하게 성립해야 한다.
 */
class OrderStateTransitionTest {

	private static final LocalDateTime ACCEPTED_AT = LocalDateTime.of(2026, 1, 1, 0, 0);
	private static final LocalDateTime SETTLED_AT = ACCEPTED_AT.plusSeconds(3);

	private Order accepted() {
		return new Order("ORD-1", "REQ-1", 1L, 2, 30_000L, ACCEPTED_AT);
	}

	@Test
	@DisplayName("접수된 주문의 시작 상태는 ACCEPTED이며 종결시각이 없다")
	void 접수_직후() {
		Order order = accepted();

		assertThat(order.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
		assertThat(order.getStatus().isTerminal()).isFalse();
		assertThat(order.getSettledAt()).isNull();
		assertThat(order.getFailureReason()).isNull();
	}

	@Test
	@DisplayName("접수됨 → 확정됨")
	void 확정으로_전이한다() {
		Order order = accepted();

		order.confirm(SETTLED_AT);

		assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
		assertThat(order.getSettledAt()).isEqualTo(SETTLED_AT);
		assertThat(order.getFailureReason()).isNull();
	}

	@Test
	@DisplayName("접수됨 → 실패함. 사유가 함께 남는다")
	void 실패로_전이한다() {
		Order order = accepted();

		order.fail(FailureReason.OUT_OF_STOCK, SETTLED_AT);

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
		assertThat(order.getFailureReason()).isEqualTo(FailureReason.OUT_OF_STOCK);
		assertThat(order.getSettledAt()).isEqualTo(SETTLED_AT);
	}

	@Nested
	@DisplayName("종결된 주문은 되돌아가지 않는다")
	class TerminalStatusIsImmutable {

		@Test
		@DisplayName("확정된 주문은 다시 확정할 수 없다")
		void 확정_후_확정() {
			Order order = accepted();
			order.confirm(SETTLED_AT);

			assertThatThrownBy(() -> order.confirm(SETTLED_AT.plusSeconds(1)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("종결된 주문");
		}

		@Test
		@DisplayName("확정된 주문은 실패로 바꿀 수 없다")
		void 확정_후_실패() {
			Order order = accepted();
			order.confirm(SETTLED_AT);

			assertThatThrownBy(() -> order.fail(FailureReason.TIMEOUT, SETTLED_AT.plusSeconds(1)))
				.isInstanceOf(IllegalStateException.class);

			assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
			assertThat(order.getFailureReason()).isNull();
		}

		@Test
		@DisplayName("실패한 주문은 확정으로 바꿀 수 없다")
		void 실패_후_확정() {
			Order order = accepted();
			order.fail(FailureReason.PAYMENT_DECLINED, SETTLED_AT);

			assertThatThrownBy(() -> order.confirm(SETTLED_AT.plusSeconds(1)))
				.isInstanceOf(IllegalStateException.class);

			assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
		}

		@Test
		@DisplayName("실패한 주문은 다시 실패할 수 없다. 사유가 덮어씌워지지 않는다")
		void 실패_후_실패() {
			Order order = accepted();
			order.fail(FailureReason.OUT_OF_STOCK, SETTLED_AT);

			assertThatThrownBy(() -> order.fail(FailureReason.TIMEOUT, SETTLED_AT.plusSeconds(1)))
				.isInstanceOf(IllegalStateException.class);

			assertThat(order.getFailureReason()).isEqualTo(FailureReason.OUT_OF_STOCK);
			assertThat(order.getSettledAt()).isEqualTo(SETTLED_AT);
		}
	}
}
