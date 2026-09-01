package com.study.monolithic_architecture.controller.dto;

/**
 * 발급된 토큰. Authorization 헤더에 {@code Bearer <accessToken>} 형태로 실어 보낸다.
 */
public record TokenResponse(String accessToken, long expiresInSeconds) {
}
