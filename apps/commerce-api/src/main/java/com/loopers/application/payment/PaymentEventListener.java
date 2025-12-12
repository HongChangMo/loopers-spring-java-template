package com.loopers.application.payment;

import com.loopers.application.order.OrderCompensationService;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.event.CardPaymentRequestedEvent;
import com.loopers.domain.payment.event.PointPaymentRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final PaymentProcessor paymentProcessor;
    private final OrderService orderService;
    private final OrderCompensationService compensationService;

    /**
     * 포인트 결제 이벤트 처리
     * - 트랜잭션 커밋 후 비동기 실행
     * - 이벤트 리스너 자체는 독립 트랜잭션 (REQUIRES_NEW)
     * - PaymentProcessor는 해당 트랜잭션 내에서 실행 (REQUIRED)
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePointPaymentRequest(PointPaymentRequestedEvent event) {
        try {
            // 이미 처리된 주문인지 확인 (멱등성)
            if (checkedOrderStatusComplete(event.getOrderId())) {
                return;
            }

            // 포인트 결제 처리
            paymentProcessor.processPointPayment(
                    event.getUserId(),
                    event.getOrderId()
            );
        } catch (Exception e) {
            log.error("포인트 결제 실패 : {}", event.getOrderId(), e);

            // 보상 트랜잭션 실행 (포인트, 재고, 쿠폰 복구)
            Order order = orderService.getOrderById(event.getOrderId());
            compensationService.compensateOrderWithPointRefund(order.getId());

            throw e;
        }
    }

    /**
     * 카드 결제 이벤트 처리
     * - 트랜잭션 커밋 후 비동기 실행
     * - 이벤트 리스너 자체는 독립 트랜잭션 (REQUIRES_NEW)
     * - PaymentProcessor는 해당 트랜잭션 내에서 실행 (REQUIRED)
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleCardPaymentRequest(CardPaymentRequestedEvent event) {
        try {
            // 이미 처리된 주문인지 확인 (멱등성)
            if (checkedOrderStatusComplete(event.getOrderId())) {
                return;
            }

            // 카드 결제 처리
            paymentProcessor.processCardPayment(
                    event.getOrderId(),
                    event.getCardType(),
                    event.getCardNo()
            );
        } catch (Exception e) {
            log.error("카드 결제 실패 : {}", event.getOrderId(), e);

            // 보상 트랜잭션 실행 (재고, 쿠폰 복구)
            Order order = orderService.getOrderById(event.getOrderId());
            compensationService.compensateOrder(order.getId());

            throw e;
        }
    }

    /**
     * 주문 완료 상태 확인 (멱등성 보장)
     */
    private boolean checkedOrderStatusComplete(Long orderId) {
        Order order = orderService.getOrderById(orderId);

        // 이미 처리된 주문인지 확인
        if (order.getStatus() == OrderStatus.COMPLETED) {
            log.warn("이미 처리된 주문입니다. orderId={}", orderId);
            return true;
        }
        return false;
    }
}
