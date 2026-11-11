package com.jobradar.ingestion.internal.alerts;

import com.jobradar.ingestion.RawPosting;
import com.jobradar.ingestion.SourceConnector;
import com.jobradar.ingestion.SourceSchedule;
import com.jobradar.ingestion.internal.source.SourceRateLimitedException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reads the user's own Gmail label for Wuzzuf, Bayt and LinkedIn alert emails and turns the jobs
 * in them into postings. A Gmail filter that applies the label is the only setup on the mail side.
 *
 * <p>Guardrails: reads only messages carrying the one configured label; only the email is parsed,
 * no board page behind a link is ever requested; nothing in the mailbox is changed; postings are
 * {@code platform} postings, applied to by the user on the board itself. Tokens and message
 * content are never logged, only counts.
 *
 * <p>Polling: every 5 minutes, listing the label's messages of the last few days and fetching only
 * those not read before. "Read before" is remembered in memory; after a restart the window is read
 * once more and {@link com.jobradar.ingestion.IngestionService} drops what is already stored
 * (unique on {@code source, source_id}). This replaces Gmail's history API, which would need a
 * stored history ID per user, hence a migration, for no change in what the reader sees.
 */
@Component
@ConditionalOnProperty(name = "jobradar.ingestion.gmail.enabled", havingValue = "true", matchIfMissing = true)
class GmailAlertConnector implements SourceConnector {

    static final String SOURCE = "gmail";
    static final SourceSchedule SCHEDULE = new SourceSchedule(Duration.ofMinutes(5), 0);

    /** Message IDs remembered per user; older ones fall out and are re-read at most once. */
    private static final int REMEMBERED_MESSAGES = 2000;

    private static final Logger log = LoggerFactory.getLogger(GmailAlertConnector.class);

    private final AlertUserRepository users;
    private final GmailTokenSource tokens;
    private final GmailApi gmail;
    private final AlertParsers parsers;
    private final GmailAlertProperties properties;

    private final Map<Long, Set<String>> seen = new HashMap<>();

    GmailAlertConnector(
            AlertUserRepository users,
            GmailTokenSource tokens,
            GmailApi gmail,
            AlertParsers parsers,
            GmailAlertProperties properties) {
        this.users = users;
        this.tokens = tokens;
        this.gmail = gmail;
        this.parsers = parsers;
        this.properties = properties;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public SourceSchedule schedule() {
        return SCHEDULE;
    }

    @Override
    public synchronized List<RawPosting> fetch() {
        List<AlertUserRepository.AlertUser> alertUsers = users.usersWithGmail();
        if (alertUsers.isEmpty()) {
            return List.of();
        }
        if (!properties.clientConfigured()) {
            log.warn("Gmail alerts: users have connected Gmail but the Google OAuth client is not configured");
            return List.of();
        }
        List<RawPosting> postings = new ArrayList<>();
        for (AlertUserRepository.AlertUser user : alertUsers) {
            try {
                postings.addAll(fetchUser(user));
            } catch (SourceRateLimitedException e) {
                throw e; // Google is limiting us: stop and let the scheduler back off
            } catch (GmailAccessException e) {
                log.warn("Gmail alerts: user {} skipped: {}", user.id(), e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Gmail alerts: user {} failed: {}", user.id(), e.getClass().getSimpleName());
            }
        }
        return postings;
    }

    private List<RawPosting> fetchUser(AlertUserRepository.AlertUser user) {
        String accessToken = gmail.accessToken(tokens.refreshToken(user.tokenRef()));
        Optional<String> labelId = gmail.labelId(accessToken, properties.label());
        if (labelId.isEmpty()) {
            log.warn("Gmail alerts: user {} has no label named '{}'", user.id(), properties.label());
            return List.of();
        }

        Set<String> seenByUser = seen.computeIfAbsent(user.id(), id -> new LinkedHashSet<>());
        List<RawPosting> postings = new ArrayList<>();
        int messages = 0;
        int withoutJobs = 0;
        int skippedItems = 0;
        for (String id : gmail.messageIds(accessToken, labelId.get(), properties.lookbackDays(), properties.maxMessagesPerRun())) {
            if (seenByUser.contains(id)) {
                continue;
            }
            GmailApi.RawMessage raw = gmail.message(accessToken, id);
            AlertParsers.Parsed parsed = parsers.parse(MailParser.parse(id, raw.rfc822(), raw.receivedAt()));
            remember(seenByUser, id);
            messages++;
            skippedItems += parsed.skipped();
            if (parsed.postings().isEmpty()) {
                withoutJobs++;
            }
            postings.addAll(parsed.postings());
        }
        if (messages > 0) {
            // Counts only: no subject, sender or body ever reaches the log.
            log.info("Gmail alerts: user {}: {} new messages, {} postings, {} messages without recognised jobs, {} job links skipped",
                    user.id(), messages, postings.size(), withoutJobs, skippedItems);
        }
        return postings;
    }

    private static void remember(Set<String> seenByUser, String id) {
        seenByUser.add(id);
        if (seenByUser.size() > REMEMBERED_MESSAGES) {
            var oldest = seenByUser.iterator();
            oldest.next();
            oldest.remove();
        }
    }
}
