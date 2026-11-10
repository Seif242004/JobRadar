package com.jobradar.notifier.internal;

import com.jobradar.matching.Band;
import com.jobradar.matching.MatchScored;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Alerts by freshness band (docs/PLAN.md, "Freshness first"):
 *
 * <ul>
 *   <li>Hot with fit 85+ and under 1 hour old: pushed when its tailored CV is ready. Hot without a
 *       fit score: pushed the moment the match is scored.
 *   <li>Warm (2 to 24 hours old): one batched message every 3 hours, best first.
 *   <li>Backlog (over 24 hours): never pushed.
 *   <li>Deadline: pushed once, when the closing date is at most 3 days away.
 * </ul>
 *
 * <p>A notification row is written after each successful send, and every query skips postings
 * that already have one, so nothing is pushed twice. If a send fails nothing is recorded and the
 * posting is picked up again by the next batch.
 */
@Service
public class AlertService {

    static final Duration HOT_WINDOW = Duration.ofHours(2);
    static final Duration WARM_WINDOW = Duration.ofHours(24);
    static final double APPLY_NOW_MIN_FIT = 85.0;
    static final Duration APPLY_NOW_MAX_AGE = Duration.ofHours(1);
    static final Duration DEADLINE_WINDOW = Duration.ofDays(3);

    private final NotificationRepository notifications;
    private final TelegramClient telegram;
    private final CvLinks cvLinks;
    private final Clock clock;

    AlertService(NotificationRepository notifications, TelegramClient telegram, CvLinks cvLinks, Clock clock) {
        this.notifications = notifications;
        this.telegram = telegram;
        this.cvLinks = cvLinks;
        this.clock = clock;
    }

    /**
     * Hot band without a fit score (SPEC.md D8): instant push, since no tailored CV will follow.
     * With a fit score the push waits for the CV, see {@link #onCvReady}.
     */
    @EventListener
    public void onMatchScored(MatchScored match) {
        if (!match.eligible() || match.band() != Band.HOT || match.fitScore() != null) {
            return;
        }
        notifications.findPending(match.userId(), match.postingId()).ifPresent(posting -> {
            boolean hasLink = AlertFormatter.isUrl(posting.applyTarget());
            telegram.sendMessage(
                    posting.chatId(),
                    AlertFormatter.applyNow(posting),
                    hasLink ? "Open posting" : null,
                    hasLink ? posting.applyTarget() : null);
            notifications.recordSent(posting.userId(), posting.postingId(), NotificationType.APPLY_NOW, clock.instant());
        });
    }

    /**
     * Apply now (SPEC.md D7): fit at least 85, posting under 1 hour old, and the CV is ready.
     * Anything else stays pending and falls through to the warm batch or the dashboard.
     *
     * @return true when a push was sent
     */
    public boolean onCvReady(long userId, long postingId, long cvVersionId, double atsScore) {
        Instant now = clock.instant();
        return notifications.findPending(userId, postingId)
                .filter(posting -> posting.fitScore() != null && posting.fitScore() >= APPLY_NOW_MIN_FIT)
                .filter(posting -> Duration.between(posting.freshSince(), now).compareTo(APPLY_NOW_MAX_AGE) < 0)
                .flatMap(posting -> notifications.findCvKey(userId, cvVersionId).map(key -> {
                    boolean hasLink = AlertFormatter.isUrl(posting.applyTarget());
                    telegram.sendMessage(
                            posting.chatId(),
                            AlertFormatter.applyNowWithCv(posting, atsScore, cvLinks.presign(key)),
                            hasLink ? "Open posting" : null,
                            hasLink ? posting.applyTarget() : null);
                    notifications.recordSent(userId, postingId, NotificationType.APPLY_NOW, now);
                    return true;
                }))
                .orElse(false);
    }

    /** Warm band: every 3 hours, one message per user with everything aged 2 to 24 hours. */
    @Scheduled(cron = "${jobradar.notifier.warm-batch-cron:0 0 */3 * * *}", zone = "Africa/Cairo")
    public void sendWarmBatches() {
        Instant now = clock.instant();
        // Hot postings that could not be pushed in time (age under 2 hours) are left for the next
        // batch only once they pass 2 hours, so the hot push always gets the first chance.
        List<AlertPosting> due = notifications.findPendingByAge(now.minus(WARM_WINDOW), now.minus(HOT_WINDOW));
        byUser(due).forEach((userId, postings) -> {
            telegram.sendMessage(postings.get(0).chatId(), AlertFormatter.warmBatch(postings), null, null);
            // Every posting of the batch is marked, including those beyond the 10 listed, so the
            // remainder does not come back as a second interruption; it stays in the dashboard.
            postings.forEach(posting ->
                    notifications.recordSent(userId, posting.postingId(), NotificationType.WARM_BATCH, now));
        });
    }

    /** Deadline band: pushed once when the closing date is at most 3 days away. */
    @Scheduled(cron = "${jobradar.notifier.deadline-cron:0 30 */3 * * *}", zone = "Africa/Cairo")
    public void sendDeadlineAlerts() {
        Instant now = clock.instant();
        for (AlertPosting posting : notifications.findPendingByDeadline(now, now.plus(DEADLINE_WINDOW))) {
            boolean hasLink = AlertFormatter.isUrl(posting.applyTarget());
            telegram.sendMessage(
                    posting.chatId(),
                    AlertFormatter.deadlineSoon(posting, now),
                    hasLink ? "Open posting" : null,
                    hasLink ? posting.applyTarget() : null);
            notifications.recordSent(posting.userId(), posting.postingId(), NotificationType.DEADLINE_SOON, now);
        }
    }

    /** Groups by user, keeping the repository's best-first order inside each group. */
    private static Map<Long, List<AlertPosting>> byUser(List<AlertPosting> postings) {
        return postings.stream().collect(Collectors.groupingBy(AlertPosting::userId));
    }
}
