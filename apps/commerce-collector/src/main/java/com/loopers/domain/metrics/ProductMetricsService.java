package com.loopers.domain.metrics;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ProductMetricsService {
    private final ProductMetricsRepository productMetricsRepository;

    /**
     * 좋아요 수 증가
     */
    @Transactional
    public void incrementLikeCount(Long productId) {
        ProductMetrics metrics = getOrCreateMetrics(productId);
        metrics.incrementLikeCount();
    }

    /**
     * 좋아요 수 감소
     */
    @Transactional
    public void decrementLikeCount(Long productId) {
        ProductMetrics metrics = getOrCreateMetrics(productId);
        metrics.decrementLikeCount();
    }

    /**
     * 주문 수 증가
     */
    @Transactional
    public void incrementOrderCount(Long productId, int quantity) {
        ProductMetrics metrics = getOrCreateMetrics(productId);
        metrics.incrementOrderCount(quantity);
    }

    /**
     * 상품 조회 수 증가
     * */
    @Transactional
    public void incrementViewCount(Long productId) {
        ProductMetrics metrics = getOrCreateMetrics(productId);
        metrics.incrementViewCount();
    }

    /**
     * Metrics 조회 또는 생성
     */
    private ProductMetrics getOrCreateMetrics(Long productId) {
        return productMetricsRepository.findByProductId(productId)
                .orElseGet(() -> {
                    ProductMetrics newMetrics = ProductMetrics.create(productId);
                    return productMetricsRepository.save(newMetrics);
                });
    }
}
