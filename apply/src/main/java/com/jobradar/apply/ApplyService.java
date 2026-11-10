package com.jobradar.apply;

import com.jobradar.apply.ApplyOutcome.Status;
import com.jobradar.apply.internal.ApplyContext;
import com.jobradar.apply.internal.ApplyProperties;
import com.jobradar.apply.internal.ApplyRepository;
import com.jobradar.apply.internal.CvPdfReader;
import com.jobradar.apply.internal.EmailComposer;
import com.jobradar.apply.internal.GmailNotConnectedException;
import com.jobradar.apply.internal.GmailSender;
import com.jobradar.apply.internal.MailTarget;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Applies to a posting on the user's behalf.
 *
 * <p><b>Guardrail 2: nothing is submitted without an explicit approval.</b> {@link #approve} is
 * the only method that sends anything, and its only caller is the handler of the user's own
 * Approve tap in the notifier. There is no scheduled or automatic path to it.
 *
 * <p>Order of events: checks, then a {@code sending} row, then the send, then the row becomes
 * {@code applied}. The row comes first so that a repeated tap or a crash can never produce a
 * second email; it is removed again only when the send itself failed, so the user can retry.
 */
@Service
public class ApplyService {

    private static final Logger log = LoggerFactory.getLogger(ApplyService.class);

    private final ApplyRepository repository;
    private final CvPdfReader cvReader;
    private final GmailSender gmail;
    private final ApplyProperties properties;
    private final Clock clock;

    ApplyService(
            ApplyRepository repository,
            CvPdfReader cvReader,
            GmailSender gmail,
            ApplyProperties properties,
            Clock clock) {
        this.repository = repository;
        this.cvReader = cvReader;
        this.gmail = gmail;
        this.properties = properties;
        this.clock = clock;
    }

    /** Sends the tailored CV by email to the posting's address, from the user's own Gmail. */
    public ApplyOutcome approve(long userId, long postingId, long cvVersionId) {
        Optional<ApplyContext> found = repository.loadContext(userId, postingId, cvVersionId);
        if (found.isEmpty()) {
            return ApplyOutcome.of(Status.NOT_FOUND, "This posting or CV is no longer available.");
        }
        ApplyContext context = found.get();
        Optional<String> recipient = "email".equals(context.applyChannel())
                ? MailTarget.parse(context.applyTarget())
                : Optional.empty();
        if (recipient.isEmpty()) {
            return ApplyOutcome.of(Status.NO_EMAIL_TARGET, "This posting has no email address to apply to.");
        }
        if (context.gmailTokenRef() == null) {
            return ApplyOutcome.of(Status.GMAIL_NOT_CONNECTED, "Gmail is not connected, so nothing was sent.");
        }
        Instant now = clock.instant();
        if (repository.emailsSinceStartOfDay(userId, now, properties.zone()) >= context.dailyApplyCap()) {
            return ApplyOutcome.of(
                    Status.CAP_REACHED, "Daily limit of " + context.dailyApplyCap() + " applications reached.");
        }
        Optional<Long> applicationId = repository.begin(userId, postingId, cvVersionId, "email");
        if (applicationId.isEmpty()) {
            return ApplyOutcome.of(Status.ALREADY_APPLIED, "You already applied to this posting.");
        }
        try {
            byte[] pdf = cvReader.read(context.pdfKey());
            gmail.send(context.gmailTokenRef(), EmailComposer.compose(context, recipient.get(), pdf));
        } catch (GmailNotConnectedException e) {
            repository.abandon(applicationId.get());
            return ApplyOutcome.of(Status.GMAIL_NOT_CONNECTED, "Gmail needs to be connected again.");
        } catch (RuntimeException e) {
            repository.abandon(applicationId.get());
            log.warn("Sending the application for user {} posting {} failed", userId, postingId, e);
            return ApplyOutcome.of(Status.FAILED, "Sending failed. Nothing was recorded; you can try again.");
        }
        // The email is out. A failure to record that must not delete the 'sending' row, which
        // still blocks a second send.
        try {
            repository.complete(applicationId.get(), now, now.plus(properties.followUpDays(), ChronoUnit.DAYS));
        } catch (RuntimeException e) {
            log.error("Application {} was sent but could not be marked applied", applicationId.get(), e);
        }
        return ApplyOutcome.of(Status.SENT, "Sent to " + recipient.get() + ".");
    }

    /** The user applied by other means and says so: records it, sends nothing. */
    public ApplyOutcome markApplied(long userId, long postingId, long cvVersionId) {
        if (repository.loadContext(userId, postingId, cvVersionId).isEmpty()) {
            return ApplyOutcome.of(Status.NOT_FOUND, "This posting or CV is no longer available.");
        }
        Instant now = clock.instant();
        boolean created = repository.insertManual(
                userId, postingId, cvVersionId, now, now.plus(properties.followUpDays(), ChronoUnit.DAYS));
        return created
                ? ApplyOutcome.of(Status.MARKED_APPLIED, "Marked as applied.")
                : ApplyOutcome.of(Status.ALREADY_APPLIED, "You already applied to this posting.");
    }
}
