package com.loopers.infrastructure.scheduler;

import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * PROCESSING 상태인 결제에 대해 주기적으로 상태를 확인하는 스케줄러
 * - 1분 주기로 실행
 * - 콜백이 오지 않은 결제에 대해 PG사에 상태 확인 요청
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentStatusCheckScheduler {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;

    // 최대 확인 횟수 (10회까지 재시도)
    private static final int MAX_CHECK_COUNT = 10;
    // 확인 주기 (1분)
    private static final int CHECK_INTERVAL_MINUTES = 1;

    /**
     * 1분마다 PROCESSING 상태인 결제 확인
     */
    @Scheduled(fixedRate = 60000) // 60초 = 1분
    @Transactional
    public void checkProcessingPayments() {
        log.info("[결제 상태 확인 스케줄러] 결제 상태 확인 시작");

        try {
            LocalDateTime thresholdTime = LocalDateTime.now().minusMinutes(CHECK_INTERVAL_MINUTES);

            // 확인 대상 결제 조회
            List<Payment> processingPayments = paymentRepository.findProcessingPaymentsForStatusCheck(
                    thresholdTime,
                    MAX_CHECK_COUNT
            );

            if (processingPayments.isEmpty()) {
                log.info("[결제 상태 확인 스케줄러] 확인할 처리 중인 결제가 없습니다");
                return;
            }

            log.info("[결제 상태 확인 스케줄러] 확인할 처리 중인 결제 {}건 발견",
                    processingPayments.size());

            // 각 결제에 대해 상태 확인
            for (Payment payment : processingPayments) {
                checkPaymentStatus(payment);
            }

            log.info("[결제 상태 확인 스케줄러] 결제 상태 확인 완료");
        } catch (Exception e) {
            log.error("[결제 상태 확인 스케줄러] 결제 상태 확인 중 오류 발생", e);
        }
    }

    /**
     * 개별 결제 상태 확인
     */
    private void checkPaymentStatus(Payment payment) {
        try {
            log.info("[결제 상태 확인 스케줄러] 결제 확인 중: paymentId={}, pgTransactionId={}, checkCount={}",
                    payment.getPaymentId(), payment.getPgTransactionId(), payment.getStatusCheckCount());

            // PG사에 상태 확인 요청
            PaymentResult result = paymentGateway.checkPaymentStatus(payment.getPgTransactionId());

            // 확인 횟수 증가
            payment.incrementStatusCheckCount();

            // 결과에 따라 결제 상태 업데이트
            if (result.isSuccess()) {
                payment.completePayment();
                log.info("[결제 상태 확인 스케줄러] 결제 완료: paymentId={}", payment.getPaymentId());
            } else if ("FAILED".equals(result.status()) || "FAIL".equals(result.status())) {
                payment.failPayment(result.message());
                log.warn("[결제 상태 확인 스케줄러] 결제 실패: paymentId={}, reason={}",
                        payment.getPaymentId(), result.message());
            } else {
                // PROCESSING 상태 유지
                log.info("[결제 상태 확인 스케줄러] 결제 처리 중: paymentId={}",
                        payment.getPaymentId());
            }

            paymentRepository.save(payment);

        } catch (Exception e) {
            log.error("[결제 상태 확인 스케줄러] 결제 상태 확인 중 오류 발생: paymentId={}",
                    payment.getPaymentId(), e);

            // 에러 발생 시에도 확인 횟수는 증가
            payment.incrementStatusCheckCount();
            paymentRepository.save(payment);
        }
    }
}