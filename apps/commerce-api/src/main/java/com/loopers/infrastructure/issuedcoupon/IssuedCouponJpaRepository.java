package com.loopers.infrastructure.issuedcoupon;

import com.loopers.domain.issuedcoupon.IssuedCoupon;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IssuedCouponJpaRepository extends JpaRepository<IssuedCoupon, Long> {
    Optional<IssuedCoupon> findByUserIdAndCouponId(Long userId, Long couponId);
}
