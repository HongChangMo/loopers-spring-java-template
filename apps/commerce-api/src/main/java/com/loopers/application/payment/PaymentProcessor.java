package com.loopers.application.payment;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.event.OrderCompletedEvent;
import com.loopers.domain.payment.*;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 처리 컴포넌트
 *
 * 포인트 결제: 자체 트랜잭션에서 동기 처리
 * 카드 결제: Payment 저장은 별도 트랜잭션, PG 장애 시에도 PENDING으로 저장
 *
 * - PG 장애 시 Payment는 PENDING 상태로 저장
 * - 스케줄러가 나중에 PENDING Payment를 재확인 가능
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentProcessor {

    @Value("${payment.callback.base-url}")
    private String callbackBaseUrl;

    private final TransactionTemplate transactionTemplate;
    private final PaymentService paymentService;
    private final PaymentGateway paymentGateway;
    private final UserService userService;
    private final OrderService orderService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 포인트 결제 처리 (새 트랜잭션에서 처리)
     *
     * @param userId 결제할 사용자 ID
     * @param orderId 결제할 주문 ID
     *
     * 처리 순서:
     * 1. User와 Order 조회 (영속 상태로 가져옴)
     * 2. Payment 생성 (PENDING)
     * 3. 포인트 차감
     * 4. Payment 즉시 완료 (PENDING → SUCCESS)
     * 5. Payment 저장
     * 6. 주문 완료 처리 (OrderStatus.COMPLETED)
     */
    @Transactional
    public void processPointPayment(Long userId, Long orderId) {
        // 1. User와 Order 조회
        User user = userService.getUserById(userId);
        Order order = orderService.getOrderById(orderId);

        // 2. Payment 생성 (PENDING)
        Payment payment = Payment.createPaymentForPoint(
                order,
                order.getTotalPrice(),
                PaymentType.POINT
        );

        // 3. 포인트 차감
        user.usePoint(order.getTotalPrice());

        // 4. Payment 즉시 완료 (PENDING → SUCCESS)
        payment.completePointPayment();

        // 5. Payment 저장
        paymentService.save(payment);

        // 6. 주문 완료 처리
        order.completeOrder();

        // 7. 주문 완료 이벤트 발행 (데이터 플랫폼 전송용)
        eventPublisher.publishEvent(
                OrderCompletedEvent.of(
                        order.getId(),
                        user.getId(),
                        order.getTotalPrice().getAmount().toString(),
                        payment.getPaymentId(),
                        payment.getPaymentType().name()
                )
        );
    }

    /**
     * 카드 결제 처리 (Payment 저장은 별도 트랜잭션)
     *
     * @param orderId 결제할 주문 ID
     * @param cardType 카드 타입
     * @param cardNo 카드 번호
     *
     * 처리 순서:
     * 1. Order 조회
     * 2. 별도 트랜잭션에서 Payment 생성 및 저장 (TransactionTemplate 사용)
     * 3. PG 결제 요청 (비동기)
     *    - PG 즉시 실패: Payment는 PENDING 유지
     *    - PG 성공: Payment → PROCESSING
     *    - PG 호출 실패: Payment는 PENDING 유지 (Scheduler 재시도 대상)
     * 4. 주문 상태 업데이트
     *    - PROCESSING인 경우: OrderStatus.RECEIVED
     *    - PENDING인 경우: 상태 변경 없음 (나중에 처리)
     *
     * PG 장애 시에도 Payment는 PENDING으로 저장되어 Scheduler가 재시도 가능
     */
    @Transactional
    public void processCardPayment(Long orderId, CardType cardType, String cardNo) {
        // 1. Order 조회
        Order order = orderService.getOrderById(orderId);

        // 2. 별도 트랜잭션에서 Payment 생성 및 저장 (TransactionTemplate 사용)
        Payment payment = transactionTemplate.execute(status -> {
            Payment p = Payment.createPaymentForCard(
                    order,
                    order.getTotalPrice(),
                    PaymentType.CARD,
                    cardType,
                    cardNo
            );
            paymentService.save(p);

            try {
                // 3. PG 결제 요청 (비동기)
                String callbackUrl = callbackBaseUrl + "/api/v1/payments/callback";
                PaymentResult result = paymentGateway.processPayment(order.getUser().getUserId(), p, callbackUrl);

                if ("FAIL".equals(result.status())) {
                    log.warn("PG에서 즉시 실패 응답. orderId={}, paymentId={}",
                            order.getId(), p.getPaymentId());
                    return p;
                }

                p.startProcessing(result.transactionId());
                log.info("PG 결제 요청 성공. orderId={}, paymentId={}, transactionId={}",
                        order.getId(), p.getPaymentId(), result.transactionId());

            } catch (Exception e) {
                log.error("PG 호출 실패. Payment는 PENDING으로 유지. orderId={}, paymentId={}",
                        order.getId(), p.getPaymentId(), e);
            }

            return p;
        });

        // TransactionTemplate.execute()는 항상 non-null 반환
        // 4. 주문 상태 업데이트
        if (payment.getStatus() == PaymentStatus.PROCESSING) {
            order.updateStatus(OrderStatus.RECEIVED);
        }
    }
}
