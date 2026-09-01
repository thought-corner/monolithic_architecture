package com.study.monolithic_architecture.controller;

import tools.jackson.databind.ObjectMapper;

import com.study.monolithic_architecture.security.JwtProvider;
import com.study.monolithic_architecture.domain.Product;
import com.study.monolithic_architecture.controller.dto.OrderAcceptRequest;
import com.study.monolithic_architecture.repository.ProductRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.study.monolithic_architecture.TestcontainersConfiguration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 엔드포인트의 수용 기준. 상태 코드와 응답 형태만 본다.
 * 처리 결과의 관찰은 시나리오 테스트(S1~S3)가 맡는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class OrderApiTest {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	ObjectMapper objectMapper;
	@Autowired
	ProductRepository productRepository;
	@Autowired
	JwtProvider jwtProvider;

	@Test
	@DisplayName("FR-01: 상품 목록은 이름·가격·재고 수량을 함께 준다")
	void fr01_상품_목록() throws Exception {
		productRepository.save(new Product("목록상품", 30_000L, 10));

		mockMvc.perform(get("/api/products"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].name").exists())
			.andExpect(jsonPath("$[0].price").exists())
			.andExpect(jsonPath("$[0].stockQuantity").exists());
	}

	@Test
	@DisplayName("FR-02: 없는 상품이면 404")
	void fr02_없는_상품() throws Exception {
		mockMvc.perform(get("/api/products/{id}", 999_999L))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
	}

	@Test
	@DisplayName("FR-03: 유효한 요청은 주문번호와 접수됨을 즉시 준다")
	void fr03_주문_접수() throws Exception {
		Product product = productRepository.save(new Product("접수상품", 30_000L, 10));

		mockMvc.perform(post("/api/orders")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(new OrderAcceptRequest(requestId(), product.getId(), 1))))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.orderNo").exists())
			.andExpect(jsonPath("$.status").value("ACCEPTED"));
	}

	@Test
	@DisplayName("FR-04: 수량이 범위 밖이면 접수 자체가 거절된다")
	void fr04_수량_범위_밖() throws Exception {
		Product product = productRepository.save(new Product("범위상품", 30_000L, 10));

		mockMvc.perform(post("/api/orders")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(new OrderAcceptRequest(requestId(), product.getId(), 11))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	@DisplayName("FR-04: 없는 상품이면 접수 자체가 거절된다")
	void fr04_없는_상품() throws Exception {
		mockMvc.perform(post("/api/orders")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(new OrderAcceptRequest(requestId(), 999_999L, 1))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
	}

	@Test
	@DisplayName("FR-08: 없는 주문이면 404")
	void fr08_없는_주문() throws Exception {
		mockMvc.perform(get("/api/orders/{orderNo}", "ORD-없는번호"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
	}

	@Test
	@DisplayName("FR-09: 상태로 필터할 수 있고, 해석할 수 없는 값은 400")
	void fr09_목록_필터() throws Exception {
		mockMvc.perform(get("/api/orders").param("status", "CONFIRMED"))
			.andExpect(status().isOk());

		mockMvc.perform(get("/api/orders").param("status", "없는상태"))
			.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("FR-10: 접수된 주문은 이력을 갖는다")
	void fr10_이력_조회() throws Exception {
		Product product = productRepository.save(new Product("이력상품", 30_000L, 10));

		String response = mockMvc.perform(post("/api/orders")
				.header(HttpHeaders.AUTHORIZATION, bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(new OrderAcceptRequest(requestId(), product.getId(), 1))))
			.andReturn().getResponse().getContentAsString();
		String orderNo = objectMapper.readTree(response).get("orderNo").asText();

		mockMvc.perform(get("/api/orders/{orderNo}/histories", orderNo))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].toStatus").value("ACCEPTED"));
	}

	@Test
	@DisplayName("없는 경로는 404다. 캐치올이 스프링의 판정을 500으로 바꾸면 안 된다")
	void 없는_경로는_404() throws Exception {
	    mockMvc.perform(get("/api/없는경로"))
	            .andExpect(status().isNotFound());
	}

	private String body(Object value) throws Exception {
		return objectMapper.writeValueAsString(value);
	}

	/** NFR-07: 주문 접수는 인증된 요청만 받는다. */
	private String bearer() {
		return "Bearer " + jwtProvider.issue("buyer-1");
	}

	private String requestId() {
		return "REQ-" + UUID.randomUUID();
	}
}
