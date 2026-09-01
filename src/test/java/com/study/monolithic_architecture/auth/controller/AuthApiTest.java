package com.study.monolithic_architecture.auth.controller;

import com.study.monolithic_architecture.TestcontainersConfiguration;
import com.study.monolithic_architecture.auth.controller.dto.TokenRequest;
import com.study.monolithic_architecture.auth.service.JwtProvider;
import com.study.monolithic_architecture.order.controller.dto.OrderAcceptRequest;
import com.study.monolithic_architecture.product.domain.Product;
import com.study.monolithic_architecture.product.repository.ProductRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NFR-07: 인증되지 않은 요청은 주문할 수 없다.
 *
 * <p>인증은 주문 접수에만 걸린다. 상품 조회까지 잠그면 NFR-04와 부딪힌다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class AuthApiTest {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	ObjectMapper objectMapper;
	@Autowired
	ProductRepository productRepository;
	@Autowired
	JwtProvider jwtProvider;

	@Test
	@DisplayName("NFR-07: 토큰 없는 주문 요청은 401")
	void 토큰_없으면_401() throws Exception {
		Product product = productRepository.save(new Product("무토큰상품", 30_000L, 10));

		mockMvc.perform(post("/api/orders")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(new OrderAcceptRequest(requestId(), product.getId(), 1))))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("NFR-07: 서명이 맞지 않는 토큰도 401")
	void 위조된_토큰이면_401() throws Exception {
		Product product = productRepository.save(new Product("위조상품", 30_000L, 10));

		mockMvc.perform(post("/api/orders")
				.header(HttpHeaders.AUTHORIZATION, "Bearer eyJhbGciOiJIUzI1NiJ9.forged.signature")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(new OrderAcceptRequest(requestId(), product.getId(), 1))))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("발급받은 토큰으로는 주문할 수 있다")
	void 유효한_토큰이면_접수된다() throws Exception {
		Product product = productRepository.save(new Product("인증상품", 30_000L, 10));

		String tokenResponse = mockMvc.perform(post("/api/auth/token")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(new TokenRequest("buyer-1"))))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String accessToken = objectMapper.readTree(tokenResponse).get("accessToken").asText();

		mockMvc.perform(post("/api/orders")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(new OrderAcceptRequest(requestId(), product.getId(), 1))))
			.andExpect(status().isAccepted());
	}

	@Test
	@DisplayName("NFR-04: 상품 조회는 토큰 없이도 된다")
	void 상품_조회는_인증_대상이_아니다() throws Exception {
		mockMvc.perform(get("/api/products"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("NFR-07: 세미콜론 파라미터를 붙여도 인증을 우회할 수 없다")
	void 경로_변형으로_우회할_수_없다() throws Exception {
	    Product product = productRepository.save(new Product("우회상품", 30_000L, 10));

	    mockMvc.perform(post("/api/orders;a=1")
	                    .contentType(MediaType.APPLICATION_JSON)
	                    .content(body(new OrderAcceptRequest(requestId(), product.getId(), 1))))
	            .andExpect(status().isUnauthorized())
	            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	private String body(Object value) throws Exception {
		return objectMapper.writeValueAsString(value);
	}

	private String requestId() {
		return "REQ-" + UUID.randomUUID();
	}
}
