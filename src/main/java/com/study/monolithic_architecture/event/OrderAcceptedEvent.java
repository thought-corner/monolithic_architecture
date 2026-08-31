package com.study.monolithic_architecture.event;

/**
 * 주문이 접수됐다. 커밋된 뒤 비동기로 처리가 시작된다. (NFR-01)
 */
public record OrderAcceptedEvent(String orderNo) {
}
