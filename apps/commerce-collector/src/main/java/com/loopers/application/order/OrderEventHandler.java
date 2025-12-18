package com.loopers.application.order;

import com.loopers.application.product.cache.ProductCacheService;
import com.loopers.domain.eventhandled.EventHandledService;
import com.loopers.domain.metrics.ProductMetricsService;
import com.loopers.domain.stock.StockThresholdChecker;
import com.loopers.infrastructure.client.ProductApiClient;
import com.loopers.infrastructure.client.dto.ProductDetailExternalDto;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventHandler {

    private final EventHandledService eventHandledService;
    private final ProductMetricsService productMetricsService;
    private final ProductApiClient productApiClient;
    private final StockThresholdChecker stockThresholdChecker;
    private final ProductCacheService productCacheService;

    @Transactional
    public void handleOrderCreated(String eventId, Long productId, int quantity) {
        // 1. 멱등성 체크
        if (eventHandledService.isAlreadyHandled(eventId)) {
            log.info("이미 처리된 주문 이벤트 - eventId: {}", eventId);
            return;
        }

        // 2. 주문 집계 증가
        productMetricsService.incrementOrderCount(productId, quantity);
        log.info("주문 집계 증가 - productId: {}, quantity: {}", productId, quantity);

        // 3. 상품 재고 확인
        int currentStock = getCurrentStock(productId);

        // 4. 재고 임계값 체크
        if (stockThresholdChecker.isBelowThreshold(currentStock)) {
            // 5. 캐시 무효화
            productCacheService.evictProductCache(productId);
            log.info("재고 임계값 도달로 캐시 무효화 - productId: {}, 현재 재고: {}",
                    productId, currentStock);
        }

        // 6. 이벤트 처리 완료 기록
        eventHandledService.markAsHandled(
                eventId, "OrderCreated", "ORDER", productId.toString()
        );

        log.info("주문 이벤트 처리 완료 - eventId: {}, productId: {}, 현재 재고: {}",
                eventId, productId, currentStock);
    }

    /**
     * commerce-api에서 현재 재고 조회
     */
    private int getCurrentStock(Long productId) {
        try {
            ProductDetailExternalDto.ProductDetailResponse product = productApiClient.getProductDetail(productId);
            return product.getStockQuantity();
        } catch (FeignException.NotFound e) {
            log.error("상품을 찾을 수 없음 - productId: {}", productId, e);
            throw new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다");
        } catch (FeignException e) {
            log.error("상품 정보 조회 실패 - productId: {}", productId, e);
            // Feign 실패 시 기본값 반환 (캐시 무효화하지 않음)
            // 재시도 로직은 Kafka Consumer의 재처리로 커버
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 정보 조회 실패");
        }
    }
}
