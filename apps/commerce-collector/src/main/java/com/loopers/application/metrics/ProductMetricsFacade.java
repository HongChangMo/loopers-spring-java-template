package com.loopers.application.metrics;

import com.loopers.domain.metrics.ProductMetrics;
import com.loopers.domain.metrics.ProductMetricsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductMetricsFacade {
    private final ProductMetricsRepository productMetricsRepository;
    private final JdbcTemplate jdbcTemplate;

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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateLikeCountBatch(Map<Long, Integer> likeDeltas) {

        if (likeDeltas.isEmpty()) {
            return;
        }

        String sql = """
            INSERT INTO product_metrics
                (product_id, like_count, order_count, view_count, total_order_quantity, created_at, updated_at)
            VALUES (?, GREATEST(?, 0), 0, 0, 0, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                like_count = like_count + VALUES(like_count),
                updated_at = NOW()
            """;

        List<Map.Entry<Long, Integer>> entries = new ArrayList<>(likeDeltas.entrySet());

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Map.Entry<Long, Integer> entry = entries.get(i);
                ps.setLong(1, entry.getKey());      // product_id
                ps.setInt(2, entry.getValue());      // like_count delta
            }

            @Override
            public int getBatchSize() {
                return entries.size();
            }
        });

        log.info("ProductMetrics Upsert 완료 - {} 건", entries.size());
    }

    /**
     * Metrics 조회 또는 생성 (비관적 락 사용)
     * 동시성 제어: 같은 productId에 대한 동시 접근 시 순차 처리
     */
    private ProductMetrics getOrCreateMetrics(Long productId) {
        // 비관적 락을 걸고 조회 (다른 트랜잭션은 대기)
        return productMetricsRepository.findByProductIdWithLock(productId)
                .orElseGet(() -> {
                    try {
                        // 락을 획득했고 없으면 생성
                        ProductMetrics newMetrics = ProductMetrics.create(productId);
                        return productMetricsRepository.save(newMetrics);
                    } catch (Exception e) {
                        // Unique constraint violation 시 재조회
                        // (드물지만 락 획득 전 다른 트랜잭션이 생성한 경우)
                        return productMetricsRepository.findByProductIdWithLock(productId)
                                .orElseThrow(() -> new RuntimeException("ProductMetrics 조회 실패: " + productId, e));
                    }
                });
    }
}
