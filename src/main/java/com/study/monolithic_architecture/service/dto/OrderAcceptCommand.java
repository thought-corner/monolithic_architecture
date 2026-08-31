package com.study.monolithic_architecture.service.dto;

/**
 * 주문 접수 지시. 서비스가 받는 입력이다.
 *
 * <p>웹의 검증 애노테이션을 달지 않는다. 이 타입은 HTTP를 모르며,
 * 표현 계층이 무엇이든 서비스는 같은 입력을 받는다.
 * 수량의 최종 방어선은 여기가 아니라 도메인 생성자다. (BR-1)
 */
public record OrderAcceptCommand(String requestId, Long productId, int quantity) {
}
