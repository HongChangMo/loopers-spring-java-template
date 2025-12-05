package com.loopers.infrastructure.scheduler;

import com.loopers.domain.Money;
import com.loopers.domain.order.Order;
import com.loopers.domain.payment.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentStatusCheckSchedulerTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @InjectMocks
    private PaymentStatusCheckScheduler scheduler;

    private Payment processingPayment;

    @BeforeEach
    void setUp() {
        // PROCESSING 상태의 결제 생성
        processingPayment = Payment.createPaymentForCard(
                mock(Order.class),
                Money.of(10000L),
                PaymentType.CARD,
                CardType.SAMSUNG,
                "1234567890123456"
        );
        processingPayment.startProcessing("test-pg-transaction-id");
    }

    @Test
    @DisplayName("PROCESSING 상태인 결제가 없으면 아무 작업도 수행하지 않는다")
    void checkProcessingPayments_NoPayments() {
        // given
        given(paymentRepository.findProcessingPaymentsForStatusCheck(any(LocalDateTime.class), anyInt()))
                .willReturn(Collections.emptyList());

        // when
        scheduler.checkProcessingPayments();

        // then
        verify(paymentRepository).findProcessingPaymentsForStatusCheck(any(LocalDateTime.class), anyInt());
        verify(paymentGateway, never()).checkPaymentStatus(anyString());
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("PROCESSING 상태인 결제가 있으면 상태를 확인하고 SUCCESS로 업데이트한다")
    void checkProcessingPayments_UpdateToSuccess() {
        // given
        List<Payment> processingPayments = Collections.singletonList(processingPayment);

        given(paymentRepository.findProcessingPaymentsForStatusCheck(any(LocalDateTime.class), anyInt()))
                .willReturn(processingPayments);
        given(paymentGateway.checkPaymentStatus("test-pg-transaction-id"))
                .willReturn(new PaymentResult("test-pg-transaction-id", "SUCCESS", "결제 완료"));

        // when
        scheduler.checkProcessingPayments();

        // then
        verify(paymentRepository).findProcessingPaymentsForStatusCheck(any(LocalDateTime.class), anyInt());
        verify(paymentGateway).checkPaymentStatus("test-pg-transaction-id");
        verify(paymentRepository).save(processingPayment);
    }

    @Test
    @DisplayName("PROCESSING 상태인 결제가 있으면 상태를 확인하고 FAILED로 업데이트한다")
    void checkProcessingPayments_UpdateToFailed() {
        // given
        List<Payment> processingPayments = Collections.singletonList(processingPayment);

        given(paymentRepository.findProcessingPaymentsForStatusCheck(any(LocalDateTime.class), anyInt()))
                .willReturn(processingPayments);
        given(paymentGateway.checkPaymentStatus("test-pg-transaction-id"))
                .willReturn(new PaymentResult("test-pg-transaction-id", "FAILED", "결제 실패"));

        // when
        scheduler.checkProcessingPayments();

        // then
        verify(paymentRepository).findProcessingPaymentsForStatusCheck(any(LocalDateTime.class), anyInt());
        verify(paymentGateway).checkPaymentStatus("test-pg-transaction-id");
        verify(paymentRepository).save(processingPayment);
    }

    @Test
    @DisplayName("PROCESSING 상태가 유지되면 확인 횟수만 증가한다")
    void checkProcessingPayments_StillProcessing() {
        // given
        List<Payment> processingPayments = Collections.singletonList(processingPayment);

        given(paymentRepository.findProcessingPaymentsForStatusCheck(any(LocalDateTime.class), anyInt()))
                .willReturn(processingPayments);
        given(paymentGateway.checkPaymentStatus("test-pg-transaction-id"))
                .willReturn(new PaymentResult("test-pg-transaction-id", "PROCESSING", "결제 처리 중"));

        // when
        scheduler.checkProcessingPayments();

        // then
        verify(paymentRepository).findProcessingPaymentsForStatusCheck(any(LocalDateTime.class), anyInt());
        verify(paymentGateway).checkPaymentStatus("test-pg-transaction-id");
        verify(paymentRepository).save(processingPayment);
    }

    @Test
    @DisplayName("여러 개의 PROCESSING 결제를 순차적으로 확인한다")
    void checkProcessingPayments_MultiplePayments() {
        // given
        Payment payment1 = Payment.createPaymentForCard(
                mock(Order.class),
                Money.of(10000L),
                PaymentType.CARD,
                CardType.SAMSUNG,
                "1234567890123456"
        );
        payment1.startProcessing("pg-tx-1");

        Payment payment2 = Payment.createPaymentForCard(
                mock(Order.class),
                Money.of(20000L),
                PaymentType.CARD,
                CardType.KB,
                "9876543210987654"
        );
        payment2.startProcessing("pg-tx-2");

        List<Payment> processingPayments = Arrays.asList(payment1, payment2);

        given(paymentRepository.findProcessingPaymentsForStatusCheck(any(LocalDateTime.class), anyInt()))
                .willReturn(processingPayments);
        given(paymentGateway.checkPaymentStatus("pg-tx-1"))
                .willReturn(new PaymentResult("pg-tx-1", "SUCCESS", "결제 완료"));
        given(paymentGateway.checkPaymentStatus("pg-tx-2"))
                .willReturn(new PaymentResult("pg-tx-2", "FAILED", "결제 실패"));

        // when
        scheduler.checkProcessingPayments();

        // then
        verify(paymentRepository).findProcessingPaymentsForStatusCheck(any(LocalDateTime.class), anyInt());
        verify(paymentGateway).checkPaymentStatus("pg-tx-1");
        verify(paymentGateway).checkPaymentStatus("pg-tx-2");
        verify(paymentRepository, times(2)).save(any(Payment.class));
    }

    @Test
    @DisplayName("결제 상태 확인 중 예외가 발생해도 확인 횟수는 증가한다")
    void checkProcessingPayments_ExceptionHandling() {
        // given
        List<Payment> processingPayments = Collections.singletonList(processingPayment);

        given(paymentRepository.findProcessingPaymentsForStatusCheck(any(LocalDateTime.class), anyInt()))
                .willReturn(processingPayments);
        given(paymentGateway.checkPaymentStatus("test-pg-transaction-id"))
                .willThrow(new RuntimeException("PG 시스템 장애"));

        // when
        scheduler.checkProcessingPayments();

        // then
        verify(paymentRepository).findProcessingPaymentsForStatusCheck(any(LocalDateTime.class), anyInt());
        verify(paymentGateway).checkPaymentStatus("test-pg-transaction-id");
        verify(paymentRepository).save(processingPayment);
    }
}