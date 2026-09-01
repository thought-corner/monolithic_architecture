package com.study.monolithic_architecture.task;

import com.study.monolithic_architecture.service.ReconciliationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정산을 언제 돌릴지만 정한다.
 *
 * <p>무엇을 하는지는 모른다. 그것은 {@link ReconciliationService}의 몫이다.
 * 이 클래스가 바뀌는 이유는 '주기를 바꾼다' 하나뿐이고, 정산 절차가 바뀌는 것은 이 클래스와 무관하다.
 *
 * <p>이름을 ReconciliationTask로 하지 않은 이유는 도메인의 CompensationTask가 이미 '되돌려야 할 일 한 건'이라는 뜻으로 Task를 쓰고 있기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationScheduler {

	private final ReconciliationService reconciliationService;

	/**
	 * 예외를 밖으로 내보내지 않는다. 한 번의 실패로 정산이 멈추면 미결 보상이 영영 해소되지 않는다. 다음 주기에 다시 시도한다.
	 */
	@Scheduled(fixedDelayString = "${order.reconciliation-interval}")
	public void run() {
		try {
			reconciliationService.reconcile();
		} catch (RuntimeException e) {
			log.error("정산이 실패했다. 다음 주기에 다시 시도한다", e);
		}
	}
}
