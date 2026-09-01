package com.study.monolithic_architecture.auth.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 토큰 발급 요청.
 *
 * <p>PRD에 자격 증명 모델이 없다. §5의 어떤 엔티티에도 비밀번호가 없으므로
 * 여기서는 구매자 식별자만 받는다. 실제 인증 수단이 정해지면 이 타입이 먼저 바뀐다.
 */
public record TokenRequest(@NotBlank(message = "구매자ID는 필수다") String buyerId) {
}
