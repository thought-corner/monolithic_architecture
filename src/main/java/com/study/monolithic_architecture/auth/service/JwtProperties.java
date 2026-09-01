package com.study.monolithic_architecture.auth.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 설정.
 *
 * @param secret   HS256 서명 키. 256비트 이상이어야 하므로 32바이트 미만이면 기동 시 실패한다.
 *                 실제 환경에서는 설정 파일이 아니라 환경변수나 비밀 저장소에서 읽는다.
 * @param validity 발급된 토큰의 유효 기간
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, Duration validity) {
}
