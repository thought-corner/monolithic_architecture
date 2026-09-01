package com.study.monolithic_architecture.order.repository;

import com.study.monolithic_architecture.order.domain.OrderStatusHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 주문 상태 이력 저장소.
 */
public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Long> {

	/**
	 * FR-10: 한 주문의 상태 변경 이력을 발생 순서대로 조회한다.
	 * 이력의 순서는 시간순으로 고정이므로 정렬을 메서드에 박는다.
	 */
	List<OrderStatusHistory> findAllByOrderNoOrderByOccurredAtAsc(String orderNo);
}
