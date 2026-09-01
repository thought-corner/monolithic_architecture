package com.study.monolithic_architecture.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * JWT 발급과 검증. (NFR-07)
 *
 * <p>서명 검증만 한다. 권한이나 역할은 다루지 않는다.
 * PRD의 액터는 구매자 하나뿐이고, 요구사항은 "인증되지 않은 요청은 주문할 수 없다"까지다.
 */
@Component
@RequiredArgsConstructor
public class JwtProvider {

	private final JwtProperties properties;
	private final Clock clock;

	/**
	 * 서명 키. 주입값에서 파생되므로 생성자가 아니라 주입 완료 후에 만든다.
	 * 매 호출마다 만들지 않도록 한 번만 계산해 둔다.
	 */
	private SecretKey key;

	@PostConstruct
	void deriveKey() {
		this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
	}

	/** 구매자에게 토큰을 발급한다. */
	public String issue(String buyerId) {
		long now = clock.millis();
		return Jwts.builder()
			.subject(buyerId)
			.issuedAt(new Date(now))
			.expiration(new Date(now + properties.validity().toMillis()))
			.signWith(key, Jwts.SIG.HS256)
			.compact();
	}

	/**
	 * 토큰을 검증하고 구매자를 돌려준다.
	 *
	 * @throws JwtException 서명이 맞지 않거나 만료됐거나 형식이 아닐 때
	 */
	public String parseBuyerId(String token) {
		Claims claims = Jwts.parser()
			.verifyWith(key)
			.clock(() -> new Date(clock.millis()))
			.build()
			.parseSignedClaims(token)
			.getPayload();
		return claims.getSubject();
	}
}
