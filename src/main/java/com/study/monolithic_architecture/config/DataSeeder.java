package com.study.monolithic_architecture.config;

import com.study.monolithic_architecture.product.domain.Product;
import com.study.monolithic_architecture.product.repository.ProductRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 로컬로 띄웠을 때 손으로 시나리오를 돌려볼 수 있도록 더미 상품을 채운다.
 *
 * <p>PRD의 액터는 구매자 하나뿐이라 상품을 만드는 API가 없다. 그래서 시드가 없으면
 * 기동 직후 상품 목록이 비어 있고, 어떤 주문도 FR-04에서 거절되어 S1~S3 중 무엇도
 * 재현할 수 없다. 이 클래스는 그 공백만 메운다. 업무 규칙은 하나도 갖지 않는다.
 *
 * <p><b>{@code local} 프로파일에서만 산다.</b> 자동으로 켜지지 않게 한 것은 의도다.
 * 기본 프로파일로 올려두면 모든 시험 컨텍스트에도 더미가 실려 시험이 보는 상태가 달라지고,
 * 무엇보다 {@link #clear()}가 행을 <b>지우는</b> 코드라 켜지는 범위를 좁게 잡아야 한다.
 * 실행: {@code SPRING_PROFILES_ACTIVE=local ./gradlew bootRun}
 */
@Slf4j
@Profile("default")
@Component
@RequiredArgsConstructor
public class DataSeeder {

    /**
     * BR-6의 거절 기준. 이 값을 사이에 두고 승인/거절 경로를 모두 재현할 수 있게 고른다.
     */
    private static final long DECLINE_THRESHOLD = 100_000L;

    private final ProductRepository productRepository;

    /**
     * 이 실행이 넣은 행의 식별자.
     *
     * <p>정리할 때 <b>남이 넣은 데이터를 건드리지 않기 위해</b> 기억한다.
     * {@code deleteAll()}로 지우면 개발자가 직접 넣어 둔 상품까지 함께 사라진다.
     */
    private final List<Long> seededIds = new ArrayList<>();

    /**
     * 기동하면서 더미 상품을 넣는다.
     *
     * <p>이 시점에는 스키마가 이미 만들어져 있다. 이 빈이 {@link ProductRepository}에 의존하고,
     * 리포지토리는 EntityManagerFactory에 의존하므로 {@code ddl-auto}가 먼저 끝난다.
     *
     * <p>같은 이름의 상품이 이미 있으면 넣지 않는다. DB를 보존하는 설정으로 바꿔 여러 번
     * 기동해도 더미가 쌓이지 않게 하기 위해서다.
     */
    @PostConstruct
    void seed() {
        if (alreadySeeded()) {
            log.info("더미 상품이 이미 있어 넣지 않는다");
            return;
        }
        productRepository.saveAll(dummyProducts())
                .forEach(product -> seededIds.add(product.getId()));
        log.info("더미 상품 {}건을 넣었다: {}", seededIds.size(), seededIds);
    }

    /**
     * 종료하면서 이 실행이 넣은 더미만 지운다.
     *
     * <p><b>정리가 끝나지 않을 수 있다.</b> Spring Boot의 docker-compose 연동은 컨텍스트가
     * 닫힐 때 MySQL 컨테이너를 내리는데, 그것이 빈 소멸보다 먼저 일어나면 커넥션이 이미 끊긴다.
     * 그래서 실패를 삼키고 경고만 남긴다. 여기서 예외가 새면 종료 로그가 스택트레이스로 뒤덮이고,
     * 정작 중요한 것은 남지 않는다. 더미가 남더라도 다음 기동의 이름 검사가 중복을 막는다.
     */
    @PreDestroy
    void clear() {
        if (seededIds.isEmpty()) {
            return;
        }
        try {
            productRepository.deleteAllById(seededIds);
            log.info("더미 상품 {}건을 지웠다", seededIds.size());
        } catch (RuntimeException e) {
            log.warn("더미 상품을 지우지 못했다. 다음 기동이 중복을 막는다", e);
        } finally {
            seededIds.clear();
        }
    }

    private boolean alreadySeeded() {
        Set<String> names = dummyProducts().stream()
                .map(Product::getName)
                .collect(Collectors.toSet());
        return productRepository.findAll().stream()
                .anyMatch(product -> names.contains(product.getName()));
    }

    /**
     * S1·S2·S3를 모두 재현할 수 있는 최소 조합.
     */
    private List<Product> dummyProducts() {
        return List.of(
                // S1: 승인되고 재고가 남아 확정까지 간다.
                new Product("노트북 거치대", 30_000L, 100),
                // S3: BR-6의 거절 기준을 넘겨 보상과 재고 원복을 재현한다.
                new Product("게이밍 모니터", DECLINE_THRESHOLD + 50_000L, 100),
                // S2: 수량 2 이상이면 가용재고가 모자란다.
                new Product("한정판 키보드", 30_000L, 1));
    }
}
