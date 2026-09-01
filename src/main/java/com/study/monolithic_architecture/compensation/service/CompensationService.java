package com.study.monolithic_architecture.compensation.service;

import com.study.monolithic_architecture.compensation.domain.CompensationProgress;
import com.study.monolithic_architecture.compensation.domain.CompensationTask;
import com.study.monolithic_architecture.compensation.domain.CompensationType;
import com.study.monolithic_architecture.compensation.repository.CompensationTaskRepository;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보상. 실패한 주문이 만든 부수효과를 되돌린다. (FR-07, BR-5)
 *
 * <p>트랜잭션 롤백이 아니라 새로운 행위로 되돌린다. 확보는 이미 커밋됐기 때문이다.
 * 되돌리기가 전부 끝나기 전에는 주문을 실패로 종결하지 않는다.
 *
 * <p>이 클래스는 순서만 잡는다. 커밋 경계는 {@link CompensationExecutor}에 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompensationService {

    /**
     * 보상 재시도 상한. 소진되면 운영 개입 대상으로 남는다.
     *
     * <p>정책 숫자이므로 설정이 아니다. 이 값을 바꾸는 것은 배포 설정 변경이 아니라
     * '언제 포기할 것인가'에 대한 설계 판단이다.
     */
    static final int MAX_ATTEMPTS = 5;

    private final CompensationTaskRepository taskRepository;
    private final CompensationExecutor executor;

    /**
     * 되돌려야 할 일을 등록한다. 아직 실행하지 않는다.
     *
     * <p>같은 종류가 이미 있으면 등록하지 않는다. 확인 없이 넣으면 재시도가 소진된 뒤
     * 정산 주기마다 같은 작업이 하나씩 무한히 쌓인다.
     */
    @Transactional
    public void enqueue(String orderNo, CompensationType type) {
        if (taskRepository.existsByOrderNoAndType(orderNo, type)) {
            return;
        }
        taskRepository.save(new CompensationTask(orderNo, type));
    }

    /**
     * 한 주문의 미결 보상을 모두 시도한다.
     */
    public void runPending(String orderNo) {
        taskRepository.findAllByOrderNo(orderNo).stream()
                .filter(CompensationTask::isPending)
                .forEach(task -> attempt(task.getId()));
    }

    /**
     * 보상 한 건을 시도한다. <b>어떤 이유로도 예외를 밖으로 던지지 않는다.</b>
     * 미결로 남으면 주문이 ACCEPTED에 머물고 정산이 이어받는다. (BR-5)
     *
     * <p>실패를 삼키는 것은 게으름이 아니라 정책이다. 호출부는 여러 주문의 보상을 순회하는
     * 반복문이며, 한 건의 실패가 새어나가면 같은 주기의 나머지 미결 보상이 통째로 건너뛰어진다.
     * 한 건이 막힌 것과 전부가 멈춘 것은 전혀 다른 사고다.
     */
    public void attempt(Long taskId) {
        try {
            runOnce(taskId);
        } catch (RuntimeException e) {
            // 시도 기록·소진 판정 단계의 실패까지 여기서 막는다.
            // 잠금 대기 초과나 커넥션 장애가 이 경로로 온다.
            log.error("보상 처리가 예기치 못하게 실패했다: task {}", taskId, e);
        }
    }

    /**
     * 되돌릴 대상이 사라진 보상을 포기한다. 소진과 같은 상태로 남겨 운영이 들여다보게 한다.
     *
     * <p>{@link #attempt}와 마찬가지로 예외를 밖으로 던지지 않는다. 호출부는 여러 주문의
     * 보상을 순회하는 반복문이다.
     */
    public void abandon(Long taskId) {
        try {
            executor.markAbandoned(taskId);
        } catch (RuntimeException e) {
            log.error("보상 포기 표시가 실패했다: task {}", taskId, e);
        }
    }

    private void runOnce(Long taskId) {
        // 이미 끝난 작업이면 조용히 넘어간다.
        if (!executor.recordAttempt(taskId)) {
            return;
        }
        try {
            executor.perform(taskId);
        } catch (RuntimeException e) {
            log.warn("보상 실패: task {}", taskId, e);
            if (executor.markExhaustedIfNoAttemptsLeft(taskId, MAX_ATTEMPTS)) {
                log.error("보상 재시도 소진: task {} — 운영 확인이 필요하다", taskId);
            }
        }
    }

    /**
     * 확정된 주문에는 되돌릴 것이 없음을 기록한다.
     *
     * <p>확보 의무(RELEASE_STOCK)는 확정에서 차감으로 바뀌며 사라진다. 남겨 두면 정산이
     * 이미 차감된 수량을 다시 놓아주려 한다. 결제 취소 의무(CANCEL_PAYMENT)도 마찬가지다.
     * 확정은 '승인된 결제로 주문이 성립했다'는 뜻이므로 취소할 대상이 아니다.
     *
     * <p>그래서 종류를 가리지 않고 미결 전부를 해소한다. 건너뛰기만 하면 그 작업은 영원히
     * 미결 목록에 남아 매 주기 조회되고, 배치 상한 아래에서는 뒤에 있는 진짜 미결 보상을 굶긴다.
     */
    @Transactional
    public void dischargeAll(String orderNo) {
        taskRepository.findAllByOrderNoAndProgress(orderNo, CompensationProgress.PENDING)
                .forEach(CompensationTask::markDone);
    }

    /**
     * BR-5: 되돌리지 못한 일이 하나라도 남아 있으면 실패로 종결할 수 없다.
     *
     * <p>재시도를 소진한 작업도 여기에 포함된다. 소진은 '되돌렸다'가 아니라 '되돌리기를
     * 포기했다'는 뜻이며, 그 상태로 주문을 닫으면 붙잡힌 재고가 영영 풀리지 않는다.
     * 이런 주문은 사람이 들여다볼 때까지 접수 상태로 남는 것이 옳다. (§8 R-6)
     */
    @Transactional(readOnly = true)
    public boolean hasUnresolved(String orderNo) {
        return taskRepository.existsByOrderNoAndProgressIn(orderNo,
                Set.of(CompensationProgress.PENDING, CompensationProgress.EXHAUSTED));
    }

    /**
     * 정산이 훑을 대상. 한 주기가 가져갈 양에 상한을 둔다.
     *
     * @param limit 이번 주기에 가져올 최대 건수. 남은 것은 다음 주기가 이어받는다.
     */
    @Transactional(readOnly = true)
    public List<CompensationTask> findPending(int limit) {
        return taskRepository.findAllByProgressOrderByIdAsc(
                CompensationProgress.PENDING, PageRequest.of(0, limit));
    }
}
