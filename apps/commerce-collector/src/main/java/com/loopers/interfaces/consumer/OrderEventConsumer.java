package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderEventHandler;
import com.loopers.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final OrderEventHandler orderEventHandler;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaTopics.ORDER,
            groupId = "commerce-collector-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeOrderEvent(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            Acknowledgment acknowledgment
    ) {
        try {
            log.info("주문 이벤트 수신 - key: {}, message: {}", key, message);

            // JSON 파싱
            JsonNode jsonNode = objectMapper.readTree(message);

            String eventId = jsonNode.get("eventId").asText();
            String eventType = jsonNode.get("eventType").asText();
            JsonNode payload = jsonNode.get("payload");

            // 이벤트 타입별 처리
            if( KafkaTopics.Order.ORDER_CREATED.equals(eventType) ) {
                Long productId = payload.get("productId").asLong();
                int quantity = payload.get("quantity").asInt();

                orderEventHandler.handleOrderCreated(eventId, productId, quantity);
            }

            // 수동 커밋
            acknowledgment.acknowledge();
            log.info("주문 이벤트 처리 완료 - eventId: {}", eventId);

        } catch (Exception e) {
            log.error("이벤트 처리 실패 - message: {}", message, e);
            // 에러 발생 시 재처리를 위해 acknowledge 하지 않음
            throw new RuntimeException("이벤트 처리 실패", e);
        }
    }
}
