package com.loopers.interfaces.consumer.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderBatchEventHandler;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.interfaces.consumer.order.dto.OrderEvent;
import com.loopers.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBatchEventConsumer {
    private final OrderBatchEventHandler orderBatchEventHandler;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaTopics.ORDER,
            groupId = "commerce-collector-batch-group",
            containerFactory = KafkaConfig.BATCH_LISTENER
    )
    public void consumeOrderBatch(
            @Payload List<String> messages,
            @Header(KafkaHeaders.RECEIVED_KEY) List<String> keys,
            Acknowledgment acknowledgment
    ) {
        log.info("주문 이벤트 배치 처리 시작 - 메시지 수: {}", messages.size());

        try {
            // 메시지들을 파싱하여 OrderEvent 리스트로 변환
            List<OrderEvent> events = parseMessages(messages, keys);

            // 파싱된 이벤트들을 핸들러로 전달
            if (!events.isEmpty()) {
                orderBatchEventHandler.handleOrderBatch(events);
                log.info("배치 처리 완료 - 처리된 이벤트 수: {}/{}", events.size(), messages.size());
            } else {
                log.warn("파싱된 이벤트가 없음 - 전체 메시지 수: {}", messages.size());
            }

            // 수동 커밋
            acknowledgment.acknowledge();

        } catch (Exception e) {
            // Business 로직 에러 - 재시도
            log.error("배치 처리 실패 (재시도) - 메시지 수: {}", messages.size(), e);
            throw new RuntimeException("배치 처리 실패", e);
        }
    }

    /**
     * 메시지 리스트를 파싱하여 OrderEvent 리스트로 변환
     * JSON 파싱 실패한 메시지는 로그만 남기고 스킵
     */
    private List<OrderEvent> parseMessages(List<String> messages, List<String> keys) {
        List<OrderEvent> events = new java.util.ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            String message = messages.get(i);
            String key = i < keys.size() ? keys.get(i) : "unknown";

            OrderEvent event = parseEvent(message, key);
            if (event != null) {
                events.add(event);
            }
        }

        return events;
    }

    /**
     * 단일 메시지를 OrderEvent로 파싱
     * 파싱 실패 시 null 반환
     */
    private OrderEvent parseEvent(String message, String key) {
        try {
            OrderEvent event = objectMapper.readValue(message, OrderEvent.class);
            log.debug("이벤트 파싱 성공 - key: {}, eventId: {}", key, event.eventId());
            return event;
        } catch (JsonProcessingException e) {
            // JSON 파싱 실패 - 로그만 남기고 스킵 (재시도 불필요)
            log.error("JSON 파싱 실패 (스킵) - key: {}, message: {}", key, message, e);
            return null;
        }
    }
}
