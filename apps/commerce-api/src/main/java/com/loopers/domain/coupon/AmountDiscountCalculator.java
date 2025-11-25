package com.loopers.domain.coupon;

import com.loopers.domain.Money;

class AmountDiscountCalculator implements DiscountCalculator {
    @Override
    public Money calculate(Money originalPrice, int discountValue) {
        // 정액 할인: 고정 금액
        return Money.of(discountValue);
    }
}