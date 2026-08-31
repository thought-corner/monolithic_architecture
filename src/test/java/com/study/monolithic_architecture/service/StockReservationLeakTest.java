package com.study.monolithic_architecture.service;

import com.study.monolithic_architecture.TestcontainersConfiguration;
import com.study.monolithic_architecture.constants.CompensationProgress;
import com.study.monolithic_architecture.constants.CompensationType;
import com.study.monolithic_architecture.domain.CompensationTask;
import com.study.monolithic_architecture.domain.Product;
import com.study.monolithic_architecture.exception.InsufficientStockException;
import com.study.monolithic_architecture.repository.CompensationTaskRepository;
import com.study.monolithic_architecture.repository.ProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 확보한 재고를 되돌릴 방법이 항상 함께 남는지 확인한다. (BR-5, FR-07)
 *
 * <p>확보만 커밋되고 되돌릴 의무가 기록되지 않으면, 처리가 끊겼을 때 붙잡힌 재고를
 * 아무도 풀지 못한다. 총재고와 확정 합은 그대로라 BR-7 검사는 통과하므로
 * 가용재고가 조용히 줄어든다. 그 창을 없앴는지 본다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StockReservationLeakTest {

    @Autowired StockReservationService reservationService;
    @Autowired ProductRepository productRepository;
    @Autowired CompensationTaskRepository taskRepository;

    @Test
    @DisplayName("확보가 성공하면 되돌릴 보상 작업이 함께 남는다")
    void 확보와_되돌릴_의무가_함께_커밋된다() {
        Product product = productRepository.save(new Product("확보상품", 30_000L, 10));
        String orderNo = newOrderNo();

        reservationService.reserveFor(orderNo, product.getId(), 2);

        assertThat(reload(product).getReservedQuantity()).isEqualTo(2);
        assertThat(releaseTasks(orderNo))
                .as("확보만 남고 되돌릴 방법이 없으면 처리가 끊겼을 때 재고가 영구 누수된다")
                .hasSize(1)
                .allMatch(task -> task.getProgress() == CompensationProgress.PENDING);
    }

    @Test
    @DisplayName("확보가 실패하면 보상 작업도 남지 않는다")
    void 확보_실패시_헛된_보상이_없다() {
        Product product = productRepository.save(new Product("품절상품", 30_000L, 1));
        String orderNo = newOrderNo();

        assertThatThrownBy(() -> reservationService.reserveFor(orderNo, product.getId(), 5))
                .isInstanceOf(InsufficientStockException.class);

        assertThat(reload(product).getReservedQuantity()).isZero();
        assertThat(releaseTasks(orderNo))
                .as("확보가 롤백됐으므로 되돌릴 것도 없어야 한다")
                .isEmpty();
    }

    private List<CompensationTask> releaseTasks(String orderNo) {
        return taskRepository.findAllByOrderNo(orderNo).stream()
                .filter(task -> task.getType() == CompensationType.RELEASE_STOCK)
                .toList();
    }

    private Product reload(Product product) {
        return productRepository.findById(product.getId()).orElseThrow();
    }

    private String newOrderNo() {
        return "ORD-" + UUID.randomUUID();
    }
}
