package com.jobradar.dashboard;

import com.jobradar.matching.internal.PostingMatcher;
import com.jobradar.profile.ProfileService;
import com.jobradar.profile.UserPreferences;
import com.jobradar.tracker.TrackedApplication;
import com.jobradar.tracker.TrackerService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single-user views for the dashboard. The user is the first row of {@code app_user}.
 * The only write is the search preferences; nothing here sends or approves an application.
 */
@RestController
@Profile({"local-dashboard", "dashboard"})
@RequestMapping("/api/dashboard")
class DashboardController {

    record Summary(
            String email,
            int postings,
            int eligibleMatches,
            int hotOrWarmWithCv,
            int applications,
            int replies,
            int followUpsDue) {}

    record MatchRow(
            long postingId,
            String title,
            String company,
            String location,
            String band,
            Double fit,
            String reasons,
            String applyChannel,
            String applyTarget,
            Instant firstSeenAt,
            boolean cvReady) {}

    record PreferencesView(
            List<String> roles,
            List<String> cities,
            boolean remote,
            List<String> jobTypes,
            boolean entryLevelOnly,
            int maxYearsExperience,
            List<String> titleKeywords,
            List<String> excludeKeywords,
            boolean onboarded) {}

    private final JdbcTemplate jdbc;
    private final TrackerService tracker;
    private final ProfileService profiles;
    private final PostingMatcher matcher;

    DashboardController(JdbcTemplate jdbc, TrackerService tracker, ProfileService profiles, PostingMatcher matcher) {
        this.jdbc = jdbc;
        this.tracker = tracker;
        this.profiles = profiles;
        this.matcher = matcher;
    }

    @GetMapping("/preferences")
    Optional<PreferencesView> preferences() {
        return userId().flatMap(id -> profiles.findPreferences(id).map(p -> view(p, profiles.isOnboarded(id))));
    }

    /** Saves the preferences, then re-filters the stored postings in the background. */
    @PutMapping("/preferences")
    PreferencesView savePreferences(@RequestBody PreferencesView body) {
        long id = userId().orElseThrow(() -> new IllegalStateException("No user yet"));
        int years = Math.max(0, Math.min(body.maxYearsExperience(), 20));
        profiles.savePreferences(id, new ProfileService.EditablePreferences(
                body.roles(), body.cities(), body.remote(), body.jobTypes(), body.entryLevelOnly(), years,
                body.titleKeywords(), body.excludeKeywords()));
        CompletableFuture.runAsync(() -> matcher.rematch(id));
        return view(profiles.findPreferences(id).orElseThrow(), true);
    }

    private static PreferencesView view(UserPreferences p, boolean onboarded) {
        return new PreferencesView(p.roles(), p.cities(), p.remote(), p.jobTypes(), p.entryLevelOnly(),
                p.maxYearsExperience(), p.titleKeywords(), p.excludeKeywords(), onboarded);
    }

    private Optional<Long> userId() {
        return jdbc.queryForList("SELECT id FROM app_user ORDER BY id LIMIT 1", Long.class).stream().findFirst();
    }

    @GetMapping("/summary")
    Optional<Summary> summary() {
        return userId().map(id -> new Summary(
                jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, id),
                jdbc.queryForObject("SELECT count(*) FROM posting", Integer.class),
                count("SELECT count(*) FROM match WHERE user_id = ? AND eligible", id),
                count("SELECT count(DISTINCT m.posting_id) FROM match m JOIN cv_version v ON v.user_id = m.user_id AND v.posting_id = m.posting_id WHERE m.user_id = ? AND m.eligible AND m.band IN ('hot', 'warm')", id),
                count("SELECT count(*) FROM application WHERE user_id = ? AND status <> 'sending'", id),
                count("SELECT count(*) FROM application WHERE user_id = ? AND status IN ('replied', 'interview', 'offer')", id),
                count("SELECT count(*) FROM application WHERE user_id = ? AND status = 'applied' AND follow_up_at <= now()", id)));
    }

    /** Eligible matches, best first: by priority once fit scores exist, then newest. */
    @GetMapping("/matches")
    List<MatchRow> matches(@RequestParam(defaultValue = "50") int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return userId()
                .map(id -> jdbc.query(
                        """
                        SELECT p.id, p.title, c.name AS company, p.location, m.band, m.fit_score, m.reasons,
                               p.apply_channel, p.apply_target, p.first_seen_at,
                               EXISTS (SELECT 1 FROM cv_version v WHERE v.user_id = m.user_id AND v.posting_id = m.posting_id) AS cv_ready
                        FROM match m
                        JOIN posting p ON p.id = m.posting_id
                        LEFT JOIN company c ON c.id = p.company_id
                        WHERE m.user_id = ? AND m.eligible
                        ORDER BY m.priority DESC NULLS LAST, p.first_seen_at DESC
                        LIMIT ?
                        """,
                        (rs, n) -> new MatchRow(
                                rs.getLong("id"), rs.getString("title"), rs.getString("company"), rs.getString("location"),
                                rs.getString("band"),
                                rs.getBigDecimal("fit_score") == null ? null : rs.getBigDecimal("fit_score").doubleValue(),
                                rs.getString("reasons"), rs.getString("apply_channel"), rs.getString("apply_target"),
                                rs.getTimestamp("first_seen_at").toInstant(), rs.getBoolean("cv_ready")),
                        id, capped))
                .orElse(List.of());
    }

    @GetMapping("/applications")
    List<TrackedApplication> applications(@RequestParam(defaultValue = "50") int limit) {
        return userId().map(id -> tracker.recent(id, Math.max(1, Math.min(limit, 200)))).orElse(List.of());
    }

    private int count(String sql, long userId) {
        return jdbc.queryForObject(sql, Integer.class, userId);
    }
}
