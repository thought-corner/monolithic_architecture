package com.study.monolithic_architecture.compensation.service.handler;

import com.study.monolithic_architecture.compensation.domain.CompensationType;
import com.study.monolithic_architecture.order.domain.Order;
import com.study.monolithic_architecture.order.repository.OrderRepository;
import com.study.monolithic_architecture.order.service.OrderHistoryService;
import com.study.monolithic_architecture.product.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 붙잡아 둔 재고를 놓아준다. 총재고는 그대로다. (FR-07)
 */
@Component
@RequiredArgsConstructor
public class ReleaseStockHandler implements CompensationHandler {

    private final OrderRepository orderRepository;
    private final StockService stockService;
    private final OrderHistoryService historyService;

    @Override
    public CompensationType type() {
        return CompensationType.RELEASE_STOCK;
    }

    @Override
    public void compensate(String orderNo) {
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        stockService.release(order.getProductId(), order.getQuantity());
        historyService.recordWithinAccepted(orderNo, "재고 확보 해제");
    }
}
