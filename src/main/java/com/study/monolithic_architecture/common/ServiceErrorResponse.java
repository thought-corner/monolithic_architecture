package com.study.monolithic_architecture.common;

/**
 * 오류 응답.
 *
 * <p>이름에 Service를 붙인 이유는 스프링의 {@code org.springframework.web.ErrorResponse}와
 * 구분하기 위해서다. 둘이 같은 이름이면 예외 처리기가 스프링이 판정한 상태 코드를
 * 살리려 할 때마다 정규화된 이름을 써야 하고, 그러면 읽기 어려워진다.
 *
 * @param code    기계가 읽는 코드
 * @param message 사람이 읽는 설명
 */
public record ServiceErrorResponse(String code, String message) {

    public static ServiceErrorResponse of(String code, String message) {
        return new ServiceErrorResponse(code, message);
    }
}
