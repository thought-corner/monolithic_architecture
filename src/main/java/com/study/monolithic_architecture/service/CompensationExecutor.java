package com.study.monolithic_architecture.service;

import com.study.monolithic_architecture.domain.CompensationTask;
import com.study.monolithic_architecture.repository.CompensationTaskRepository;
import com.study.monolithic_architecture.service.compensation.CompensationHandlers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 보상 작업의 트랜잭션 단위. CompensationService가 순서를 잡고 여기가 커밋 경계를 만든다.
 *
 * <p>시도 기록·되돌리기·실패 판정을 각각 다른 트랜잭션에 두는 이유:
 * 되돌리기가 실패하면 그 트랜잭션은 롤백되어야 하지만, "몇 번 시도했는가"는 남아야 한다.
 * 한 트랜잭션에 묶으면 재시도 횟수가 매번 롤백되어 영원히 소진되지 않는다.
 *
 * <p>같은 클래스 안에서 부르면 프록시를 우회해 트랜잭션이 걸리지 않으므로 빈을 나눴다.
 *
 * <p><b>무엇을 되돌리는지는 모른다.</b> 종류별 구현은 CompensationHandler들이 갖고 있다.
 * 이 클래스는 커밋 경계와 상태 표시만 책임진다.
 */
@Service
@RequiredArgsConstructor
public class CompensationExecutor {

    private final CompensationTaskRepository taskRepository;
    private final CompensationHandlers handlers;
    private final Clock clock;

    /**
     * 시도했다는 사실을 먼저 커밋한다. 뒤이은 실패가 이 기록까지 지우면 안 된다.
     *
     * <p>이미 끝난 작업이면 아무것도 하지 않고 false를 돌려준다. 예외를 던지면
     * 호출부의 반복문 전체가 중단되어, 같은 주기의 다른 미결 보상까지 건너뛰게 된다.
     *
     * @return 시도를 기록했으면 true. 이미 끝난 작업이면 false.
     */
    @Transactional
    public boolean recordAttempt(Long taskId) {
        CompensationTask task = task(taskId);
        if (!task.isPending()) {
            return false;
        }
        task.recordAttempt(LocalDateTime.now(clock));
        return true;
    }

    /**
     * 되돌리기와 완료 표시를 한 트랜잭션에 묶는다.
     * 재고를 놓아준 뒤 표시 직전에 죽으면 재시도가 같은 수량을 두 번 놓아주기 때문이다.
     */
    @Transactional
    public void perform(Long taskId) {
        CompensationTask task = task(taskId);
        if (!task.isPending()) {
            return;
        }
        handlers.of(task.getType()).compensate(task.getOrderNo());
        task.markDone();
    }

    /**
     * 더 시도할 수 없다고 판단해 소진으로 표시한다. 재시도 횟수와 무관하다.
     *
     * <p>되돌릴 대상 자체가 사라진 경우에 쓴다. 그냥 건너뛰면 배치 상한 아래에서
     * 한 칸을 영구히 차지해 뒤에 있는 진짜 미결 보상을 굶긴다.
     */
    @Transactional
    public void markAbandoned(Long taskId) {
        CompensationTask task = task(taskId);
        if (task.isPending()) {
            task.markExhausted();
        }
    }

    /** 재시도가 남아 있지 않으면 소진으로 표시한다. 주문은 여전히 ACCEPTED로 남는다. */
    @Transactional
    public boolean markExhaustedIfNoAttemptsLeft(Long taskId, int maxAttempts) {
        CompensationTask task = task(taskId);
        if (task.isPending() && !task.hasAttemptsLeft(maxAttempts)) {
            task.markExhausted();
            return true;
        }
        return false;
    }

    /**
     * 작업을 잠그고 가져온다. 잠금 없이 읽으면 두 스레드가 같은 미결 보상을 함께 통과시킨다.
     *
     * <p>잠금은 트랜잭션 경계와 함께 풀린다. 그래서 {@link #perform}처럼 되돌리기와
     * 완료 표시를 한 트랜잭션에 묶어야 "수행했으나 표시하지 못한" 창이 생기지 않는다.
     */
    private CompensationTask task(Long taskId) {
        return taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new IllegalStateException("보상 작업을 찾을 수 없다: " + taskId));
    }
}
