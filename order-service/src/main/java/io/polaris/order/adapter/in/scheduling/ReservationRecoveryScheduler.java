package io.polaris.order.adapter.in.scheduling;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import io.polaris.order.ReservationRecoveryProperties;
import io.polaris.order.application.port.in.RecoverPendingOrdersUseCase;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ReservationRecoveryProperties.class)
public class ReservationRecoveryScheduler {
    private static final String POLL_INTERVAL = "${polaris.reservation.recovery.poll-interval:5s}";
    private static final String INITIAL_DELAY = "${polaris.reservation.recovery.initial-delay:30s}";
    private final RecoverPendingOrdersUseCase recovery;

    public ReservationRecoveryScheduler(RecoverPendingOrdersUseCase recovery) {
        this.recovery = recovery;
    }

    @Scheduled(fixedDelayString = POLL_INTERVAL, initialDelayString = INITIAL_DELAY)
    public void recover() {
        recovery.recoverPending();
    }
}
