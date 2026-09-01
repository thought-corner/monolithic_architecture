package com.study.monolithic_architecture.controller;

import lombok.RequiredArgsConstructor;

import com.study.monolithic_architecture.security.JwtProperties;
import com.study.monolithic_architecture.security.JwtProvider;
import com.study.monolithic_architecture.controller.dto.TokenRequest;
import com.study.monolithic_architecture.controller.dto.TokenResponse;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 토큰 발급. PRD 확장이다.
 *
 * <p>NFR-07이 요구하는 것은 "토큰 없는 주문은 401"까지이며 발급 방법은 정하지 않았다.
 * 그럼에도 이 엔드포인트를 두는 이유는, 발급 수단이 없으면 주문 API를 쓸 수 없기 때문이다.
 *
 * <p><b>지금은 자격 증명을 확인하지 않는다.</b> 요구하는 사람 누구에게나 발급한다.
 * PRD §5에 비밀번호도 계정도 없기 때문이며, 이 지점은 요구사항이 정해지면 채워야 한다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final JwtProvider jwtProvider;
	private final JwtProperties jwtProperties;

	@PostMapping("/token")
	public TokenResponse issue(@Valid @RequestBody TokenRequest request) {
		return new TokenResponse(
			jwtProvider.issue(request.buyerId()),
			jwtProperties.validity().toSeconds());
	}
}
