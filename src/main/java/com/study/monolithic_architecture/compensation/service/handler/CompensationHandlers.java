package com.study.monolithic_architecture.compensation.service.handler;

import com.study.monolithic_architecture.compensation.domain.CompensationType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 보상 종류별 구현을 모아 둔다.
 *
 * <p>기동 시점에 한 번만 짝을 맞춘다. 같은 종류를 두 구현이 담당하면 여기서 바로 실패하고,
 * 담당자가 없는 종류를 부르면 명확한 예외가 난다. 런타임에 조용히 아무것도 안 하는 것보다 낫다.
 */
@Component
public class CompensationHandlers {

    private final Map<CompensationType, CompensationHandler> byType =
            new EnumMap<>(CompensationType.class);

    public CompensationHandlers(List<CompensationHandler> handlers) {
        for (CompensationHandler handler : handlers) {
            CompensationHandler previous = byType.put(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "보상 종류 %s 를 담당하는 구현이 둘이다: %s, %s".formatted(
                                handler.type(),
                                previous.getClass().getSimpleName(),
                                handler.getClass().getSimpleName()));
            }
        }
    }

    public CompensationHandler of(CompensationType type) {
        CompensationHandler handler = byType.get(type);
        if (handler == null) {
            throw new IllegalStateException("보상 종류를 담당하는 구현이 없다: " + type);
        }
        return handler;
    }
}
