package com.study.monolithic_architecture.service;

import com.study.monolithic_architecture.TestcontainersConfiguration;
import com.study.monolithic_architecture.constants.OrderStatus;
import com.study.monolithic_architecture.domain.Order;
import com.study.monolithic_architecture.domain.Product;
import com.study.monolithic_architecture.repository.CompensationTaskRepository;
import com.study.monolithic_architecture.repository.OrderRepository;
import com.study.monolithic_architecture.repository.ProductRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 확정과 보상은 <b>같은 순서로</b> 행을 잠가야 한다.
 *
 * <p>보상({@code CompensationExecutor.perform})은 진입 즉시 compensation_tasks를
 * {@code FOR UPDATE}로 잠근 뒤 products를 갱신한다. 확정({@code OrderSettlementService.confirm})이
 * 그 반대 순서로 잠그면 두 트랜잭션이 서로의 행을 기다려 교착이 된다.
 *
 * <p>여기서는 보상이 하는 일을 별도 커넥션으로 그대로 재현하고, 그 사이에 진짜 확정을 돌린다.
 * 확정이 products를 먼저 쥔 채 작업 행을 기다리면 교착이 나고, 작업 행을 먼저 기다리면 나지 않는다.
 * 시계나 대기 시간에 기대지 않고 잠금만으로 순서를 강제하므로 결과가 흔들리지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SettlementLockOrderTest {

    /**
     * 확정이 정말 막혀 있는지 확인하는 시간. 이 안에 끝나면 잠금 재현이 실패한 것이다.
     */
    private static final long BLOCKED_CHECK_SECONDS = 2;

    /**
     * 잠금이 풀린 뒤 확정이 끝나기를 기다리는 상한.
     */
    private static final long COMPLETION_TIMEOUT_SECONDS = 30;

    @Autowired
    DataSource dataSource;
    @Autowired
    OrderSettlementService settlementService;
    @Autowired
    StockReservationService reservationService;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    ProductRepository productRepository;
    @Autowired
    CompensationTaskRepository taskRepository;

    @Test
    @DisplayName("보상이 작업 행을 쥐고 있어도 확정이 교착으로 죽지 않는다")
    void 확정과_보상이_교착되지_않는다() throws Exception {
        Product product = productRepository.save(new Product("교착상품", 30_000L, 10));
        Order order = accepted(product, 2);
        reservationService.reserveFor(order.getOrderNo(), product.getId(), 2);
        Long taskId = taskRepository.findAllByOrderNo(order.getOrderNo()).get(0).getId();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection compensating = dataSource.getConnection()) {
            compensating.setAutoCommit(false);

            // 보상의 1단계: 작업 행을 잠근다.
            lockTaskRow(compensating, taskId);

            Future<?> confirming = pool.submit(() -> settlementService.confirm(order.getOrderNo()));

            // 확정은 작업 행을 기다리느라 막혀 있어야 한다. 끝나버리면 잠금 재현이 실패한 것이다.
            assertThatThrownBy(() -> confirming.get(BLOCKED_CHECK_SECONDS, TimeUnit.SECONDS))
                    .as("확정이 작업 행을 건드리지 않았다면 이 시험은 락 순서를 검증하지 못한다")
                    .isInstanceOf(TimeoutException.class);

            // 보상의 2단계: 재고를 놓아준다. 확정이 products를 먼저 쥐고 있었다면 여기서 교착이다.
            assertThatCode(() -> releaseStock(compensating, product.getId()))
                    .as("확정이 products를 먼저 잠갔다면 두 트랜잭션이 서로를 기다려 교착이 된다")
                    .doesNotThrowAnyException();
            compensating.commit();

            assertThatCode(() -> confirming.get(COMPLETION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("교착 패자가 되면 확정이 실패하고 주문은 결제 승인된 채 접수 상태로 남는다")
                    .doesNotThrowAnyException();
        } finally {
            pool.shutdownNow();
        }

        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    /**
     * {@code CompensationExecutor.perform}이 진입 즉시 하는 일.
     */
    private void lockTaskRow(Connection conn, Long taskId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "select id from compensation_tasks where id = ? for update")) {
            ps.setLong(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
            }
        }
    }

    /**
     * {@code ReleaseStockHandler}가 그다음 하는 일.
     */
    private void releaseStock(Connection conn, Long productId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "update products set reserved_quantity = reserved_quantity where id = ?")) {
            ps.setLong(1, productId);
            ps.executeUpdate();
        }
    }

    private Order accepted(Product product, int quantity) {
        String suffix = UUID.randomUUID().toString();
        return orderRepository.save(new Order("ORD-" + suffix, "REQ-" + suffix,
                product.getId(), quantity, product.getPrice(), LocalDateTime.now()));
    }

    private Order reload(Order order) {
        return orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
    }
}
