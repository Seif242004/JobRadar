package com.jobradar.notifier.internal;

import com.jobradar.notifier.internal.TelegramClient.Tap;
import com.jobradar.notifier.internal.TelegramClient.Updates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fetches button taps by long polling, so the api needs no public endpoint. The offset lives in
 * memory: after a restart Telegram re-sends taps it has not seen confirmed, and every action is
 * idempotent (see {@code application_user_posting_key}).
 */
@Component
@ConditionalOnProperty(name = "jobradar.notifier.listener.enabled", havingValue = "true", matchIfMissing = true)
public class TelegramTapPoller {

    private static final Logger log = LoggerFactory.getLogger(TelegramTapPoller.class);

    private final TelegramClient telegram;
    private final DecisionHandler decisions;
    private long offset;

    TelegramTapPoller(TelegramClient telegram, DecisionHandler decisions) {
        this.telegram = telegram;
        this.decisions = decisions;
    }

    @Scheduled(fixedDelay = 500)
    void poll() {
        Updates updates;
        try {
            updates = telegram.getUpdates(offset, 25);
        } catch (RuntimeException e) {
            log.warn("Could not fetch Telegram updates: {}", e.getMessage());
            pause();
            return;
        }
        for (Tap tap : updates.taps()) {
            try {
                decisions.handle(tap);
            } catch (RuntimeException e) {
                // One bad tap must not block the ones after it; it is confirmed and dropped.
                log.warn("Failed to handle a Telegram tap", e);
            }
        }
        offset = updates.nextOffset();
    }

    /** Backs off after an error so an outage does not become a tight loop. */
    private static void pause() {
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
