package com.study.monolithic_architecture.domain;

import com.study.monolithic_architecture.exception.InsufficientStockException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BR-7: 어떤 시점에도 {@code 총재고 + 확정된 주문의 수량 합 = 초기 재고}.
 *
 * <p>확보가 총재고를 줄이지 않는다는 것이 이 불변식의 핵심이다.
 * 확보 시점에 총재고를 깎으면 확보와 확정 사이 구간에서 합이 초기 재고보다 작아진다.
 */
class StockInvariantTest {

	private static final int INITIAL_STOCK = 10;

	private Product product() {
		return new Product("정합성상품", 30_000L, INITIAL_STOCK);
	}

	/** 확정된 주문의 수량 합을 인자로 받아 불변식을 확인한다. */
	private void assertInvariant(Product product, int confirmedQuantity) {
		assertThat(product.getStockQuantity() + confirmedQuantity)
			.as("총재고(%d) + 확정 수량 합(%d) 은 초기 재고(%d) 와 같아야 한다",
				product.getStockQuantity(), confirmedQuantity, INITIAL_STOCK)
			.isEqualTo(INITIAL_STOCK);
	}

	@Test
	@DisplayName("초기 상태에서 성립한다")
	void 초기() {
		assertInvariant(product(), 0);
	}

	@Test
	@DisplayName("확보 직후에도 성립한다. 확보는 총재고를 줄이지 않는다")
	void 확보_직후() {
		Product product = product();

		product.reserve(3);

		assertThat(product.getStockQuantity()).isEqualTo(INITIAL_STOCK);
		assertThat(product.getReservedQuantity()).isEqualTo(3);
		assertThat(product.getAvailableQuantity()).isEqualTo(7);
		assertInvariant(product, 0);
	}

	@Test
	@DisplayName("확정 후에 성립한다. 총재고가 줄고 확보가 사라진다 (S1)")
	void 확정_후() {
		Product product = product();

		product.reserve(1);
		product.deduct(1);

		assertThat(product.getStockQuantity()).isEqualTo(9);
		assertThat(product.getReservedQuantity()).isZero();
		assertInvariant(product, 1);
	}

	@Test
	@DisplayName("원복 후에 성립한다. 총재고는 그대로 돌아온다 (S3)")
	void 원복_후() {
		Product product = product();

		product.reserve(1);
		product.release(1);

		assertThat(product.getStockQuantity()).isEqualTo(INITIAL_STOCK);
		assertThat(product.getReservedQuantity()).isZero();
		assertInvariant(product, 0);
	}

	@Test
	@DisplayName("확보와 확정과 원복이 뒤섞여도 성립한다")
	void 뒤섞인_경로() {
		Product product = product();

		product.reserve(2);
		product.reserve(3);
		product.deduct(2);   // 확정 2개
		product.release(3);  // 나머지 원복

		assertThat(product.getStockQuantity()).isEqualTo(8);
		assertThat(product.getReservedQuantity()).isZero();
		assertInvariant(product, 2);
	}

	@Test
	@DisplayName("가용재고를 넘겨 확보할 수 없다 (BR-3)")
	void 가용재고를_넘길_수_없다() {
		Product product = product();
		product.reserve(8);

		assertThatThrownBy(() -> product.reserve(3))
			.isInstanceOf(InsufficientStockException.class);

		assertThat(product.getReservedQuantity()).isEqualTo(8);
		assertInvariant(product, 0);
	}

	@Test
	@DisplayName("확보하지 않은 수량은 해제하거나 차감할 수 없다")
	void 확보하지_않은_수량() {
		Product product = product();
		product.reserve(2);

		assertThatThrownBy(() -> product.release(3)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> product.deduct(3)).isInstanceOf(IllegalStateException.class);

		assertInvariant(product, 0);
	}
}
