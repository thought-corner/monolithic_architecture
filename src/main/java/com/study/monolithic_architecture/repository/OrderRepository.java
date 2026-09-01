package com.study.monolithic_architecture.repository;

import com.study.monolithic_architecture.domain.Order;
import com.study.monolithic_architecture.constants.OrderStatus;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 주문 저장소.
 *
 * <p>정렬을 메서드 이름에 넣지 않고 {@link Sort}로 받는다.
 * 엔티티 이름이 Order라 {@code ...OrderBy...}가 연관관계처럼 읽히기 때문이다.
 * 필터 없는 최신순 목록은 상속받은 {@code findAll(Sort)}를 쓴다.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * FR-08: 주문번호로 한 건을 조회한다.
     */
    Optional<Order> findByOrderNo(String orderNo);

    /**
     * NFR-02: 같은 요청식별자로 이미 접수된 주문을 찾는다.
     * 중복 요청이면 새로 만들지 않고 이 주문을 그대로 돌려준다.
     */
    Optional<Order> findByRequestId(String requestId);

    /**
     * FR-09: 상태로 걸러 목록을 조회한다. 정렬은 호출부가 정한다.
     */
    List<Order> findAllByStatus(OrderStatus status, Sort sort);

    /**
     * 종결 데드라인을 넘긴 접수 상태의 주문. 미결 보상이 남아 있다는 신호다. (Q6)
     * 새 필드 없이 접수시각과 상태만으로 식별된다.
     */
    List<Order> findAllByStatusAndAcceptedAtBeforeOrderByIdAsc(OrderStatus status,
                                                               LocalDateTime threshold,
                                                               Pageable pageable);

    /**
     * 여러 주문을 한 번에 읽는다. 보상 작업마다 주문을 되묻으면 미결 건수만큼 쿼리가 늘어난다.
     */
    List<Order> findAllByOrderNoIn(Collection<String> orderNos);
}
