package com.loopers.interfaces.consumer.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.product.ProductBatchEventHandler;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.interfaces.consumer.product.dto.ProductEvent;
import com.loopers.kafka.KafkaTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductBatchEventConsumer {

    private final ProductBatchEventHandler productBatchEventHandler;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaTopics.PRODUCT,
            groupId = "commerce-collector-batch-group",
            containerFactory = KafkaConfig.BATCH_LISTENER
    )
    public void consumeProductViewBatch(
            @Payload List<String> messages,
            @Header(KafkaHeaders.RECEIVED_KEY) List<String> keys,
            Acknowledgment acknowledgment
    ) {
        try {

            // 1. JSON 파싱
            List<ProductEvent> events = messages.stream()
                    .map(this::parseEvent)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            // 2. 배치 처리
            if( !events.isEmpty() ) {
                productBatchEventHandler.handleProductViewBatch(events);
            }

            // 3. 수동 커밋
            acknowledgment.acknowledge();
            log.info("배치 처리 완료 - 성공: {}/{}", events.size(), messages.size());

        } catch (Exception e) {
            log.error("배치 처리 실패 (재시도)", e);
            throw new RuntimeException("배치 처리 실패", e);
        }
    }

    private ProductEvent parseEvent(String message) {
        try {
            JsonNode jsonNode = objectMapper.readTree(message);

            // 필수 필드 검증
            if (!jsonNode.has("eventId") || !jsonNode.has("eventType") || !jsonNode.has("payload")) {
                log.warn("잘못된 메시지 형식 (스킵): {}", message);
                return null;
            }

            String eventId = jsonNode.get("eventId").asText();
            String eventType = jsonNode.get("eventType").asText();
            JsonNode payload = jsonNode.get("payload");

            if (!payload.has("productId")) {
                log.warn("productId 누락 (스킵): {}", message);
                return null;
            }

            Long productId = payload.get("productId").asLong();

            return new ProductEvent(
                    eventId,
                    eventType,
                    productId
            );

        } catch (JsonProcessingException e) {
            log.warn("메시지 파싱 실패: {}", message);
            return null;
        }
    }
}
