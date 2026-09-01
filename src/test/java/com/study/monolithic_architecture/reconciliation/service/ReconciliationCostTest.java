package com.study.monolithic_architecture.reconciliation.service;

import com.study.monolithic_architecture.TestcontainersConfiguration;
import com.study.monolithic_architecture.order.domain.Order;
import com.study.monolithic_architecture.order.repository.OrderRepository;
import com.study.monolithic_architecture.order.service.StockReservationService;
import com.study.monolithic_architecture.product.domain.Product;
import com.study.monolithic_architecture.product.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정산 한 주기의 비용은 미결 건수에 비례해 늘어나면 안 된다. (NFR-03)
 *
 * <p>RELEASE_STOCK은 확보에 성공한 순간 미결로 등록되므로, 평상시에도 미결 목록에는
 * <b>처리 중인 모든 주문</b>이 들어 있다. 여기서 건당 주문 조회를 하면 접수량에 비례해
 * 쿼리가 늘어나고, 한 주기가 다음 주기 안에 끝나지 않게 된다.
 *
 * <p>발행된 SQL을 직접 세는 이유는, 이 성질이 결과값이 아니라 <b>실행 비용</b>에만
 * 드러나기 때문이다. 상태만 보면 N+1이든 아니든 똑같이 통과한다.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, ReconciliationCostTest.StatementRecorder.class})
class ReconciliationCostTest {

    private static final int PENDING_TASKS = 5;

    /**
     * 주문 조회 상한. 목록 조회 한 번이면 충분하다. 건수에 비례하면 안 된다.
     */
    private static final int MAX_ORDER_SELECTS = 2;

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class StatementRecorder {
        @Bean
        HibernatePropertiesCustomizer recordStatements() {
            return (Map<String, Object> properties) -> properties.put(
                    "hibernate.session_factory.statement_inspector",
                    (StatementInspector) sql -> {
                        STATEMENTS.add(sql.replaceAll("\\s+", " "));
                        return sql;
                    });
        }
    }

    @Autowired
    ReconciliationService reconciliationService;
    @Autowired
    StockReservationService reservationService;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    ProductRepository productRepository;

    @Test
    @DisplayName("미결 보상이 늘어도 주문 조회는 건수에 비례하지 않는다")
    void 주문_조회가_건수에_비례하지_않는다() {
        Product product = productRepository.save(new Product("정산비용상품", 30_000L, 100));
        for (int i = 0; i < PENDING_TASKS; i++) {
            // 데드라인 안쪽이라 실행 대상은 아니지만, 실행 여부 판정에는 주문이 필요하다.
            Order order = accepted(product, LocalDateTime.now());
            reservationService.reserveFor(order.getOrderNo(), product.getId(), 1);
        }

        STATEMENTS.clear();
        reconciliationService.retryPendingCompensations();

        assertThat(orderSelects())
                .as("미결 %d건에 주문 조회 %d번이면 접수량에 비례해 주기 비용이 늘어난다",
                        PENDING_TASKS, orderSelects())
                .isLessThanOrEqualTo(MAX_ORDER_SELECTS);
    }

    private long orderSelects() {
        return STATEMENTS.stream()
                .map(String::toLowerCase)
                .filter(sql -> sql.startsWith("select") && sql.contains(" from orders "))
                .count();
    }

    private Order accepted(Product product, LocalDateTime acceptedAt) {
        String suffix = UUID.randomUUID().toString();
        return orderRepository.save(new Order("ORD-" + suffix, "REQ-" + suffix,
                product.getId(), 1, product.getPrice(), acceptedAt));
    }
}
