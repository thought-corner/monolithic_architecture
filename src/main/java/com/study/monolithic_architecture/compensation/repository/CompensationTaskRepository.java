package com.study.monolithic_architecture.compensation.repository;

import com.study.monolithic_architecture.compensation.domain.CompensationProgress;
import com.study.monolithic_architecture.compensation.domain.CompensationTask;
import com.study.monolithic_architecture.compensation.domain.CompensationType;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 보상 작업 저장소.
 */
public interface CompensationTaskRepository extends JpaRepository<CompensationTask, Long> {

    /**
     * 한 주문의 보상 작업 전부.
     */
    List<CompensationTask> findAllByOrderNo(String orderNo);

    /**
     * BR-5: 실패로 종결하기 전에 되돌리지 못한 일이 남아 있는지 확인한다.
     *
     * <p>PENDING만 세면 안 된다. 재시도를 소진해 EXHAUSTED가 된 작업도 '되돌리지 못한 일'이며,
     * 이것을 '미결 아님'으로 취급하면 확보한 재고를 풀지 않은 채 주문이 닫힌다.
     */
    boolean existsByOrderNoAndProgressIn(String orderNo, Collection<CompensationProgress> progresses);

    /**
     * 같은 종류의 보상이 이미 등록돼 있는지. 중복 등록을 막는다.
     * 확인 없이 등록하면 정산 주기마다 같은 작업이 하나씩 쌓인다.
     */
    boolean existsByOrderNoAndType(String orderNo, CompensationType type);

    /**
     * 정산이 다시 시도할 대상.
     *
     * <p>한 주기가 가져갈 양에 상한을 둔다. RELEASE_STOCK은 확보에 성공한 순간 미결이 되므로
     * 평상시에도 이 목록에는 처리 중인 모든 주문이 들어 있다. 상한이 없으면 접수량이 늘수록
     * 한 주기가 길어져 다음 주기를 침범한다.
     *
     * <p>등록된 순서대로 가져온다. 처리 중인 주문의 작업은 방금 만들어져 id가 크므로 뒤로 가고,
     * 데드라인을 넘긴 오래된 주문의 보상이 앞에 온다.
     *
     * <p><b>{@code last_attempted_at} 오름차순으로 정렬하지 않는다.</b> '가장 오래 손대지 않은
     * 것부터'로 읽히지만 MySQL은 ASC에서 NULL을 맨 앞에 놓는다. RELEASE_STOCK은 재고 확보에
     * 성공한 순간 미결로 등록되므로, 아직 처리 중인 주문들이 null인 채 배치를 가득 채워
     * 정작 재시도가 필요한 보상이 영영 배치에 들어오지 못한다.
     */
    List<CompensationTask> findAllByProgressOrderByIdAsc(CompensationProgress progress,
                                                         Pageable pageable);

    /**
     * 한 주문의 특정 진행 상태 작업 전부.
     */
    List<CompensationTask> findAllByOrderNoAndProgress(String orderNo, CompensationProgress progress);

    /**
     * 되돌리기를 수행하는 동안 이 작업을 독점한다.
     *
     * <p>비동기 주문 처리와 정산 스케줄러는 서로 다른 스레드이며 같은 미결 보상을 집을 수 있다.
     * 잠금 없이 {@code isPending()}만 보면 두 트랜잭션이 모두 통과해 되돌리기가 두 번 일어난다.
     * 재고 해제는 Product의 낙관적 락이 <i>우연히</i> 막아주지만, 결제 취소는 막아주는 것이 없다.
     * 대행에 취소가 두 번 나가고 이력에도 같은 사건이 두 줄 남는다. (FR-10)
     *
     * <p>낙관적 락 대신 비관적 락을 쓴다. 낙관적 락은 커밋 시점에야 충돌을 알리므로
     * <b>외부 호출은 이미 나간 뒤</b>다. 되돌릴 수 없는 부수효과 앞에서는 늦다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from CompensationTask task where task.id = :id")
    Optional<CompensationTask> findByIdForUpdate(@Param("id") Long id);
}
