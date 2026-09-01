package com.study.monolithic_architecture.config;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 비동기 처리, 정산 스케줄러, 메서드 재시도를 켠다.
 */
@Configuration
@EnableAsync
@EnableScheduling
@EnableResilientMethods(order = Ordered.HIGHEST_PRECEDENCE)
public class ProcessingConfig {

	/** 결제 대행 동시 호출 상한. 대행이 느려져도 여기까지만 물린다. */
	private static final int PAYMENT_POOL_SIZE = 16;

	/**
	 * 대기 상한. 무경계 큐를 쓰면 안 된다. 호출부가 시간 예산을 넘겨 포기한 뒤에도
	 * 큐에 남은 요청이 나중에 실행되어, 보상이 이미 끝난 주문에 승인을 만들어낸다.
	 * 여기까지 차면 즉시 거부해 '보내지 않았음'이 확실한 실패로 끝나게 한다.
	 */
	private static final int PAYMENT_QUEUE_SIZE = 64;

	/**
	 * 결제 대행 호출 전용 실행자.
	 *
	 * <p>공용 ForkJoinPool을 쓰면 안 된다. 그 풀의 병렬도는 코어 수 - 1이고,
	 * 시간 예산을 넘긴 호출은 취소되지 않은 채 스레드를 계속 점유한다. 결제가 몰리면
	 * 풀이 포화되어 뒤이은 요청이 실제 대행 지연과 무관하게 타임아웃 판정을 받고,
	 * 같은 풀을 쓰는 다른 병렬 작업까지 함께 느려진다.
	 */
	@Bean(destroyMethod = "shutdown")
	public ExecutorService paymentExecutor() {
		ThreadPoolExecutor executor = new ThreadPoolExecutor(
			PAYMENT_POOL_SIZE, PAYMENT_POOL_SIZE, 0L, TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(PAYMENT_QUEUE_SIZE),
			runnable -> {
				Thread thread = new Thread(runnable, "payment-gateway");
				thread.setDaemon(true);
				return thread;
			},
			new ThreadPoolExecutor.AbortPolicy());
		return executor;
	}

	@Bean
	public Clock clock() {
		return Clock.systemDefaultZone();
	}
}
