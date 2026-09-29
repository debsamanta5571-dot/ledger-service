package com.ledger.reporting;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Nightly trigger for {@link DailyBalanceService}. Disable with {@code ledger.jobs.daily-balances.enabled=false}. */
@Component
@ConditionalOnProperty(name = "ledger.jobs.daily-balances.enabled", havingValue = "true", matchIfMissing = true)
public class DailyBalanceJob {

    private final DailyBalanceService service;

    public DailyBalanceJob(DailyBalanceService service) {
        this.service = service;
    }

    /** Runs shortly after UTC midnight so the day that just ended is complete. */
    @Scheduled(cron = "${ledger.jobs.daily-balances.cron:0 5 0 * * *}", zone = "UTC")
    public void run() {
        service.rollUpThrough(LocalDate.now(ZoneOffset.UTC).minusDays(1));
    }
}
