package com.study.monolithic_architecture.controller;

import com.study.monolithic_architecture.exception.OrderNotFoundException;
import com.study.monolithic_architecture.exception.ProductNotFoundException;
import com.study.monolithic_architecture.exception.ServiceErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 도메인 예외를 HTTP 상태로 옮긴다.
 *
 * <p>서비스와 도메인은 HTTP를 모른다. 변환은 여기서만 일어난다.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** FR-02: 없는 상품이면 404. */
    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ServiceErrorResponse> handleProductNotFound(ProductNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ServiceErrorResponse.of("PRODUCT_NOT_FOUND", e.getMessage()));
    }

    /** FR-08: 없는 주문이면 404. */
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ServiceErrorResponse> handleOrderNotFound(OrderNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ServiceErrorResponse.of("ORDER_NOT_FOUND", e.getMessage()));
    }

    /** FR-04: 수량 범위 밖이면 접수 자체가 거절된다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ServiceErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
                .body(ServiceErrorResponse.of("INVALID_REQUEST", message));
    }

    /** 알 수 없는 상태값으로 필터하는 경우 등. (FR-09) */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ServiceErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ServiceErrorResponse.of("INVALID_REQUEST", "값을 해석할 수 없다: " + e.getName()));
    }

    /** 도메인 생성자가 던지는 불변식 위반. 표현 계층을 우회해도 여기서 걸린다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ServiceErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(ServiceErrorResponse.of("INVALID_REQUEST", e.getMessage()));
    }

    /**
     * 마지막 그물.
     *
     * <p>스프링이 스스로 상태 코드를 정해 던진 예외는 그 코드를 그대로 살린다.
     * 없는 경로 요청이 던지는 NoResourceFoundException까지 500으로 바꾸면,
     * 오타 하나가 서버 장애 응답과 ERROR 로그를 만든다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ServiceErrorResponse> handleUnexpected(Exception e) {
        if (e instanceof ErrorResponse springError) {
            HttpStatus status = HttpStatus.valueOf(springError.getStatusCode().value());
            log.debug("스프링이 판정한 오류: {} {}", status, e.getMessage());
            return ResponseEntity.status(status)
                    .body(ServiceErrorResponse.of(status.name(), status.getReasonPhrase()));
        }
        log.error("처리하지 못한 예외", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ServiceErrorResponse.of("INTERNAL_ERROR", "요청을 처리하지 못했다"));
    }
}
