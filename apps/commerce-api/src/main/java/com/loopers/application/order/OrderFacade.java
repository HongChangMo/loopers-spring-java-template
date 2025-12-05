package com.loopers.application.order;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponService;
import com.loopers.domain.issuedcoupon.IssuedCoupon;
import com.loopers.domain.issuedcoupon.IssuedCouponService;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.*;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class OrderFacade {

    private final OrderService orderService;
    private final UserService userService;
    private final ProductService productService;
    private final CouponService couponService;
    private final IssuedCouponService issuedCouponService;
    private final PaymentService paymentService;
    // 외부 결제 API 인터페이스
    private final PaymentGateway paymentGateway;

    @Transactional
    public OrderInfo createOrder(OrderCommand command) {
        // 1. User 정보 조회
        User user = userService.getUser(command.userId());

        // 2. 쿠폰 처리
        Coupon coupon = null;
        IssuedCoupon issuedCoupon = null;
        if (command.couponId() != null) {
            coupon = couponService.getValidCoupon(command.couponId());
            issuedCoupon = issuedCouponService.getIssuedCouponByUser(user.getId(), command.couponId());
        }

        // 3. 상품 조회 (Pessimistic Lock)
        Map<Product, Integer> productQuantities = getProductQuantities(command);

        // 4. 주문 생성
        Order order = Order.createOrder(user, productQuantities, coupon, issuedCoupon);

        // 5. 재고 차감
        productQuantities.forEach(Product::decreaseStock);

        // 6. 주문 저장 (Payment가 Order를 참조하기 전에 먼저 저장)
        Order savedOrder = orderService.registerOrder(order);

        // 7. 결제 방식별 처리 (Order가 이미 저장된 상태)
        processPayment(command, user, savedOrder);

        // 8. 쿠폰 사용 처리
        if (issuedCoupon != null) {
            issuedCoupon.useCoupon();
        }

        return OrderInfo.from(savedOrder);
    }

    /**
     * 상품 조회 및 검증
     */
    private Map<Product, Integer> getProductQuantities(OrderCommand command) {
        Map<Product, Integer> productQuantities = new HashMap<>();
        for (OrderCommand.OrderItemCommand item : command.items()) {
            Product product = productService.getProductWithLock(item.productId());

            if (productQuantities.containsKey(product)) {
                throw new CoreException(ErrorType.BAD_REQUEST, "동일 상품이 중복으로 요청되었습니다");
            }

            productQuantities.put(product, item.quantity());
        }
        return productQuantities;
    }

    /**
     * 결제 방식별 처리
     */
    private void processPayment(OrderCommand command, User user, Order order) {
        if (command.paymentType() == PaymentType.POINT) {
            processPointPayment(user, order);
        } else if (command.paymentType() == PaymentType.CARD) {
            processCardPayment(command, order);
        }
    }

    /**
     * 포인트 결제 처리 (동기)
     * 1. Payment 생성 (PENDING)
     * 2. 포인트 차감
     * 3. Payment 즉시 완료 (PENDING → SUCCESS)
     * 4. Payment 저장
     * 5. 주문 완료 처리
     */
    private void processPointPayment(User user, Order order) {
        // 1. Payment 생성 (PENDING)
        Payment payment = Payment.createPaymentForPoint(
                order,
                order.getTotalPrice(),
                PaymentType.POINT
        );

        // 2. 포인트 차감
        user.usePoint(order.getTotalPrice());

        // 3. Payment 즉시 완료 (PENDING → SUCCESS)
        payment.completePointPayment();

        // 4. Payment 저장
        paymentService.save(payment);

        // 5. 주문 완료 처리
        order.completeOrder();
    }

    /**
     * 카드 결제 처리 (비동기 - PG 연동)
     * 1. Payment 생성
     * 2. PG 결제 요청
     * 3. Payment 상태를 PROCESSING으로 변경
     * 4. 주문 접수 상태로 변경
     * 5. 콜백으로 최종 결과 처리 (PaymentFacade)
     */
    private void processCardPayment(OrderCommand command, Order order) {
        // 1. Payment 생성
        Payment payment = Payment.createPaymentForCard(
                order,
                order.getTotalPrice(),
                command.paymentType(),
                command.cardType(),
                command.cardNo()
        );

        // 2. PG 결제 요청 (비동기)
        String callbackUrl = "http://localhost:8080/api/v1/payments/callback";
        PaymentResult result = paymentGateway.processPayment(command.userId(), payment, callbackUrl);

        // 3. PG 결제 결과 확인
        if ("FAIL".equals(result.status())) {
            // Fallback이 호출된 경우: 결제 대기 상태로 처리
            // Payment는 이미 PENDING 상태로 생성되었으므로 상태 변경 불필요
            paymentService.save(payment);

            // 주문은 INIT 상태 유지 (나중에 재시도 가능)
            throw new CoreException(ErrorType.PAYMENT_REQUEST_FAILED,
                    "결제 시스템 장애로 인해 주문이 대기 상태입니다. 잠시 후 다시 시도해주세요.");
        }

        // 4. Payment 상태 → PROCESSING
        payment.startProcessing(result.transactionId());

        // 5. Payment 저장
        paymentService.save(payment);

        // 6. 주문 상태 → RECEIVED (접수 완료)
        order.updateStatus(OrderStatus.RECEIVED);
    }
}
