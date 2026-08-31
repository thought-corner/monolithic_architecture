package com.study.monolithic_architecture.service;

import lombok.RequiredArgsConstructor;

import com.study.monolithic_architecture.constants.OrderStatus;
import com.study.monolithic_architecture.domain.OrderStatusHistory;
import com.study.monolithic_architecture.service.dto.OrderHistoryInfo;
import com.study.monolithic_architecture.repository.OrderStatusHistoryRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 주문의 상태 변경 이력을 남기고 조회한다. (FR-10)
 *
 * <p>상태가 바뀌지 않는 사건(재고 확보, 확보 해제)도 기록한다.
 * S3가 "이력에 확보와 원복이 모두 남는다"를 요구하기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class OrderHistoryService {

	private final OrderStatusHistoryRepository historyRepository;
	private final Clock clock;

	@Transactional
	public void recordAccepted(String orderNo, String reason) {
		historyRepository.save(
			OrderStatusHistory.accepted(orderNo, reason, LocalDateTime.now(clock)));
	}

	@Transactional
	public void record(String orderNo, OrderStatus from, OrderStatus to, String reason) {
		historyRepository.save(
			new OrderStatusHistory(orderNo, from, to, reason, LocalDateTime.now(clock)));
	}

	/**
	 * 상태는 그대로지만 남겨야 하는 사건. 재고 확보와 확보 해제가 여기 해당한다.
	 */
	@Transactional
	public void recordWithinAccepted(String orderNo, String reason) {
		record(orderNo, OrderStatus.ACCEPTED, OrderStatus.ACCEPTED, reason);
	}

	@Transactional(readOnly = true)
	public List<OrderHistoryInfo> findByOrderNo(String orderNo) {
		return historyRepository.findAllByOrderNoOrderByOccurredAtAsc(orderNo).stream()
			.map(OrderHistoryInfo::from)
			.toList();
	}
}
