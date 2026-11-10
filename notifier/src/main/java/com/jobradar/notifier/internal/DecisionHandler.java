package com.jobradar.notifier.internal;

import com.jobradar.apply.ApplyOutcome;
import com.jobradar.apply.ApplyService;
import com.jobradar.notifier.internal.TelegramClient.Tap;
import com.jobradar.tracker.ApplicationStatus;
import com.jobradar.tracker.TrackerService;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns a button tap into an action. This is the explicit user approval event of guardrail 2:
 * the only code path that calls {@link ApplyService#approve} is a tap on an Approve button,
 * made by the user whose chat is linked to the account.
 */
@Component
public class DecisionHandler {

    private static final Logger log = LoggerFactory.getLogger(DecisionHandler.class);

    private final NotificationRepository notifications;
    private final ApplyService apply;
    private final TrackerService tracker;
    private final TelegramClient telegram;
    private final Clock clock;

    DecisionHandler(
            NotificationRepository notifications,
            ApplyService apply,
            TrackerService tracker,
            TelegramClient telegram,
            Clock clock) {
        this.notifications = notifications;
        this.apply = apply;
        this.tracker = tracker;
        this.telegram = telegram;
        this.clock = clock;
    }

    public void handle(Tap tap) {
        // The tap must come from the private chat of a linked user: the chat id of a private chat
        // equals the Telegram user id, so a message forwarded elsewhere cannot approve anything.
        Optional<Long> user = tap.fromId() == tap.chatId() ? notifications.findUserByChat(tap.chatId()) : Optional.empty();
        Optional<TapData> data = TapData.parse(tap.data());
        if (user.isEmpty() || data.isEmpty()) {
            telegram.answerTap(tap.callbackId(), "This button is no longer valid.");
            return;
        }
        long userId = user.get();
        switch (data.get()) {
            case TapData.Approve approve -> finish(tap, userId, approve.postingId(), "approve",
                    apply.approve(userId, approve.postingId(), approve.cvVersionId()));
            case TapData.MarkApplied marked -> finish(tap, userId, marked.postingId(), "applied",
                    apply.markApplied(userId, marked.postingId(), marked.cvVersionId()));
            case TapData.Skip skip -> {
                notifications.recordAction(userId, skip.postingId(), "skip", clock.instant());
                telegram.clearButtons(tap.chatId(), tap.messageId());
                telegram.answerTap(tap.callbackId(), "Skipped.");
            }
            case TapData.Later later -> {
                notifications.recordAction(userId, later.postingId(), "later", clock.instant());
                telegram.clearButtons(tap.chatId(), tap.messageId());
                telegram.answerTap(tap.callbackId(), "Saved for later.");
            }
            case TapData.Replied replied -> status(tap, userId, replied.applicationId(), ApplicationStatus.REPLIED, "Marked as replied.");
            case TapData.Rejected rejected -> status(tap, userId, rejected.applicationId(), ApplicationStatus.REJECTED, "Marked as rejected.");
        }
    }

    private void finish(Tap tap, long userId, long postingId, String action, ApplyOutcome outcome) {
        boolean done = switch (outcome.status()) {
            case SENT, MARKED_APPLIED, ALREADY_APPLIED -> true;
            default -> false;
        };
        if (outcome.status() == ApplyOutcome.Status.SENT || outcome.status() == ApplyOutcome.Status.MARKED_APPLIED) {
            notifications.recordAction(userId, postingId, action, clock.instant());
        }
        if (done) {
            // Removing the buttons keeps the same prompt from being tapped twice. After a
            // failure they stay, so the user can retry.
            telegram.clearButtons(tap.chatId(), tap.messageId());
        }
        telegram.answerTap(tap.callbackId(), outcome.detail());
        try {
            telegram.sendMessage(tap.chatId(), (done ? "✅ " : "⚠️ ") + outcome.detail(), null, null);
        } catch (RuntimeException e) {
            log.warn("Could not confirm the decision to user {}", userId, e);
        }
    }

    private void status(Tap tap, long userId, long applicationId, ApplicationStatus status, String text) {
        boolean updated = tracker.updateStatus(userId, applicationId, status);
        telegram.clearButtons(tap.chatId(), tap.messageId());
        telegram.answerTap(tap.callbackId(), updated ? text : "This application was not found.");
    }
}
