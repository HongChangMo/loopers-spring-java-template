package com.loopers.application.order;

import com.loopers.application.eventhandled.EventHandledFacade;
import com.loopers.application.eventhandled.EventHandledInfo;
import com.loopers.application.metrics.ProductMetricsDailyFacade;
import com.loopers.application.metrics.ProductMetricsFacade;
import com.loopers.interfaces.consumer.order.dto.OrderEvent;
import com.loopers.kafka.AggregateTypes;
import com.loopers.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBatchEventHandler {

    private final EventHandledFacade eventHandledFacade;
    private final ProductMetricsFacade productMetricsFacade;
    private final ProductMetricsDailyFacade productMetricsDailyFacade;

    @Transactional
    public void handleOrderBatch(List<OrderEvent> events) {
        try {
            log.info("주문 배치 처리 시작 - 전체 이벤트 수: {}", events.size());

            List<OrderEvent> unprocessedEvents = filterUnprocessedEvents(events);
            if (unprocessedEvents.isEmpty()) {
                log.info("처리할 이벤트 없음 (모두 중복)");
                return;
            }

            Map<String, OrderEvent> uniqueEvents = removeDuplicates(unprocessedEvents);
            Map<Long, Integer> orderDeltas = aggregateOrderQuantities(uniqueEvents.values());

            if (orderDeltas.isEmpty()) {
                log.info("집계할 주문 데이터 없음");
                return;
            }

            log.info("주문 수량 집계 완료 - 처리 대상 상품 수: {}", orderDeltas.size());

            updateMetrics(orderDeltas);
            markEventsAsHandled(uniqueEvents.values());

            log.info("주문 배치 처리 완료 - 전체: {}, 미처리: {}, 실제 처리: {}",
                    events.size(), unprocessedEvents.size(), uniqueEvents.size());

        } catch (Exception e) {
            log.error("주문 배치 처리 실패 - 전체 트랜잭션 롤백됨 | 이벤트 수: {}", events.size(), e);
            throw new RuntimeException("주문 배치 처리 실패", e);
        }
    }

    private List<OrderEvent> filterUnprocessedEvents(List<OrderEvent> events) {
        return events.stream()
                .filter(event -> !eventHandledFacade.isAlreadyHandled(event.eventId()))
                .collect(Collectors.toList());
    }

    private Map<String, OrderEvent> removeDuplicates(List<OrderEvent> events) {
        return events.stream()
                .collect(Collectors.toMap(
                        OrderEvent::eventId,
                        event -> event,
                        (existing, replacement) -> existing
                ));
    }

    private Map<Long, Integer> aggregateOrderQuantities(Iterable<OrderEvent> events) {
        Map<Long, Integer> orderDeltas = new HashMap<>();

        for (OrderEvent event : events) {
            if (!KafkaTopics.Order.ORDER_CREATED.equals(event.eventType())) {
                log.warn("알 수 없는 이벤트 타입 - eventId: {}, eventType: {}",
                        event.eventId(), event.eventType());
                continue;
            }

            processOrderCreatedEvent(event, orderDeltas);
        }

        return orderDeltas;
    }

    private void processOrderCreatedEvent(OrderEvent event, Map<Long, Integer> orderDeltas) {
        OrderEvent.OrderCreatedPayload payload = event.payload();
        if (payload == null || payload.items() == null) {
            log.error("잘못된 ORDER_CREATED 형식 - eventId: {}", event.eventId());
            return;
        }

        for (OrderEvent.OrderCreatedPayload.OrderItem item : payload.items()) {
            if (item.productId() == null || item.quantity() == null) {
                log.error("잘못된 OrderItem 형식 - eventId: {}, item: {}", event.eventId(), item);
                continue;
            }

            orderDeltas.merge(item.productId(), item.quantity(), Integer::sum);
        }
    }

    private void updateMetrics(Map<Long, Integer> orderDeltas) {
        productMetricsFacade.updateOrderCountBatch(orderDeltas);
        log.info("ProductMetrics 업데이트 완료");

        productMetricsDailyFacade.updateOrderDeltaBatch(orderDeltas, LocalDate.now());
        log.info("ProductMetricsDaily 업데이트 완료");
    }

    private void markEventsAsHandled(Iterable<OrderEvent> events) {
        List<EventHandledInfo> eventHandledInfos = createEventHandledInfos(events);
        eventHandledFacade.markAsHandledBatch(eventHandledInfos);
        log.info("이벤트 처리 완료 기록");
    }

    private List<EventHandledInfo> createEventHandledInfos(Iterable<OrderEvent> events) {
        List<EventHandledInfo> infos = new java.util.ArrayList<>();
        for (OrderEvent event : events) {
            if (KafkaTopics.Order.ORDER_CREATED.equals(event.eventType())) {
                infos.add(EventHandledInfo.of(
                        event.eventId(),
                        event.eventType(),
                        AggregateTypes.ORDER,
                        event.payload().orderId().toString()
                ));
            }
        }
        return infos;
    }
}