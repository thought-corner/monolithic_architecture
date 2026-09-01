package com.study.monolithic_architecture.domain;

import com.study.monolithic_architecture.exception.InsufficientStockException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품. 이름·가격·재고를 가진 판매 대상.
 *
 * <p>재고는 총재고와 확보수량 두 값으로 관리한다. 확보는 총재고를 줄이지 않으므로
 * {@code 총재고 + 확정된 주문의 수량 합 = 초기 재고}가 어떤 시점에도 성립한다. (BR-7)
 * 가용재고는 저장하지 않고 계산한다.
 *
 * <p>재고를 바꾸는 길은 reserve·release·deduct 셋뿐이다.
 * setStockQuantity()가 생기면 BR-7을 지킬 방법이 사라진다.
 */
@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private long price;

	/** 총재고. 상품이 실제로 보유한 수량이며 BR-7의 좌변이다. */
	@Column(nullable = false)
	private int stockQuantity;

	/** 확보수량. 아직 종결되지 않은 주문이 붙잡고 있는 수량. */
	@Column(nullable = false)
	private int reservedQuantity;

	/** 동시 주문이 재고 정합성을 깨지 못하게 한다. (BR-7) */
	@Version
	private Long version;

	public Product(String name, long price, int stockQuantity) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("상품명은 비어 있을 수 없다");
		}
		if (stockQuantity < 0) {
			throw new IllegalArgumentException("재고는 음수일 수 없다: " + stockQuantity);
		}
		this.name = name;
		this.price = price;
		this.stockQuantity = stockQuantity;
		this.reservedQuantity = 0;
	}

	/** 가용재고 = 총재고 - 확보수량. 저장하지 않는 계산값이다. */
	public int getAvailableQuantity() {
		return stockQuantity - reservedQuantity;
	}

	/**
	 * 재고를 확보한다. 총재고는 줄지 않는다. (FR-05)
	 *
	 * @throws InsufficientStockException 가용재고가 요청 수량보다 적을 때 (BR-3)
	 */
	public void reserve(int quantity) {
		if (quantity > getAvailableQuantity()) {
			throw new InsufficientStockException(id, quantity, getAvailableQuantity());
		}
		this.reservedQuantity += quantity;
	}

	/**
	 * 붙잡아 둔 수량을 놓아준다. 총재고는 그대로다. (FR-07)
	 * 실패한 주문의 재고 원복이 이것이다.
	 */
	public void release(int quantity) {
		requireReserved(quantity);
		this.reservedQuantity -= quantity;
	}

	/**
	 * 총재고를 실제로 줄인다. 주문 확정에서 단 한 번만 일어난다.
	 * 확보를 차감으로 바꾸는 것이므로 확보수량도 함께 줄인다.
	 */
	public void deduct(int quantity) {
		requireReserved(quantity);
		this.reservedQuantity -= quantity;
		this.stockQuantity -= quantity;
	}

	private void requireReserved(int quantity) {
		if (quantity > reservedQuantity) {
			throw new IllegalStateException(
				"확보되지 않은 수량이다: 상품 %d, 요청 %d, 확보 %d".formatted(id, quantity, reservedQuantity));
		}
	}
}
