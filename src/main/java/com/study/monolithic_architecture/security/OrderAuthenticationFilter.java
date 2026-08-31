package com.study.monolithic_architecture.security;

import lombok.RequiredArgsConstructor;

import com.study.monolithic_architecture.exception.ServiceErrorResponse;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 인증되지 않은 요청은 주문할 수 없다. (NFR-07)
 *
 * <p>주문 접수(FR-03)만 막는다. 상품 조회는 인증 대상이 아니다.
 * 주문이 멈춰도 상품 조회는 살아 있어야 하므로(NFR-04), 조회까지 잠그면 요구사항끼리 부딪힌다.
 *
 * <p>토큰이 없어도, 서명이 틀려도, 만료돼도 전부 401이다. 401은 "당신이 누구인지 모른다"이고,
 * 셋 다 같은 뜻이기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class OrderAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";
	private static final String ORDER_PATH = "/api/orders";

	/** 컨텍스트 경로를 걷어내고 세미콜론 파라미터를 제거해 준다. */
	private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

	/** 디스패처가 쓰는 것과 같은 매칭 엔진. 둘의 해석이 갈리면 우회가 생긴다. */
	private static final PathPattern ORDER_PATTERN =
		PathPatternParser.defaultInstance.parse(ORDER_PATH);

	/** 검증된 구매자를 뒤 계층이 꺼내 쓸 수 있게 남긴다. */
	public static final String BUYER_ID_ATTRIBUTE = "buyerId";

	private final JwtProvider jwtProvider;
	private final ObjectMapper objectMapper;

	/**
	 * 주문 접수 외에는 통과시킨다.
	 *
	 * <p>getRequestURI()를 그대로 쓰면 안 된다. 컨텍스트 경로가 붙은 배포에서는
	 * "/app/api/orders"가 되어 리터럴과 일치하지 않고, 그러면 인증이 통째로 꺼진 채
	 * 오류도 로그도 남지 않는다. 애플리케이션 기준 경로로 비교해 배포 형태와 무관하게
	 * 같은 판정이 나오게 한다. (NFR-07)
	 */
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!HttpMethod.POST.matches(request.getMethod())) {
			return true;
		}
		PathContainer path = PathContainer.parsePath(PATH_HELPER.getPathWithinApplication(request));
		return !ORDER_PATTERN.matches(path);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
		String token = bearerToken(request);
		if (token == null) {
			reject(response, "토큰이 없다");
			return;
		}
		try {
			request.setAttribute(BUYER_ID_ATTRIBUTE, jwtProvider.parseBuyerId(token));
		} catch (JwtException | IllegalArgumentException e) {
			reject(response, "토큰을 신뢰할 수 없다");
			return;
		}
		chain.doFilter(request, response);
	}

	private String bearerToken(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith(BEARER_PREFIX)) {
			return null;
		}
		String token = header.substring(BEARER_PREFIX.length()).trim();
		return token.isEmpty() ? null : token;
	}

	private void reject(HttpServletResponse response, String message) throws IOException {
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(
			objectMapper.writeValueAsString(ServiceErrorResponse.of("UNAUTHORIZED", message)));
	}
}
