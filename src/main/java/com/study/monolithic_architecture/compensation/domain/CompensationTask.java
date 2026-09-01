package com.study.monolithic_architecture.compensation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보상 작업. 실패로 종결하기 전에 반드시 끝내야 하는 되돌리기 한 건. (BR-5)
 *
 * <p>주문 상태를 늘리는 대신 이 별도 축으로 원복 진행 상황을 표현한다.
 * 미결 보상이 남아 있는 한 주문은 ACCEPTED에 머문다. (BR-4와 BR-5를 동시에 지키는 방법)
 */
@Entity
@Table(name = "compensation_tasks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompensationTask {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String orderNo;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CompensationType type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CompensationProgress progress;

	@Column(nullable = false)
	private int attempts;

	private LocalDateTime lastAttemptedAt;

	public CompensationTask(String orderNo, CompensationType type) {
		this.orderNo = orderNo;
		this.type = type;
		this.progress = CompensationProgress.PENDING;
		this.attempts = 0;
	}

	/** 한 번 시도했음을 기록한다. 성공 여부와 무관하다. */
	public void recordAttempt(LocalDateTime now) {
		requirePending();
		this.attempts++;
		this.lastAttemptedAt = now;
	}

	/** 되돌리기가 끝났다. 이미 되돌려져 있던 경우도 성공이다. */
	public void markDone() {
		requirePending();
		this.progress = CompensationProgress.DONE;
	}

	/** 최대 시도를 소진했다. 주문은 여전히 ACCEPTED로 남는다. */
	public void markExhausted() {
		requirePending();
		this.progress = CompensationProgress.EXHAUSTED;
	}

	public boolean isPending() {
		return progress == CompensationProgress.PENDING;
	}

	public boolean hasAttemptsLeft(int maxAttempts) {
		return attempts < maxAttempts;
	}

	private void requirePending() {
		if (progress != CompensationProgress.PENDING) {
			throw new IllegalStateException(
				"이미 끝난 보상 작업이다: %s / %s (%s)".formatted(orderNo, type, progress));
		}
	}
}
