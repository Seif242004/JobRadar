package com.jobradar.profile.seed;

import java.sql.Array;
import java.sql.Date;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a {@link ProfileSeed} into {@code app_user}, {@code profile}, {@code cv_entry} and
 * {@code bullet}.
 *
 * <p>The import is idempotent, and bullet IDs are stable: a bullet that already exists for the
 * user (same section and text) is left untouched, so IDs referenced by {@code cv_version} never
 * change. Bullets are never deleted here; removing one is an explicit user action.
 */
@Component
public class ProfileSeedImporter {

    /** Tag put on bullets whose wording the user has not confirmed yet. */
    static final String VERIFY_TAG = "verify";

    /** Prefix of the tag that records which entry a bullet belongs to. */
    static final String ENTRY_TAG_PREFIX = "entry:";

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    ProfileSeedImporter(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Outcome of one import run. */
    public record Result(long userId, int bulletsInserted, int bulletsAlreadyPresent) {}

    @Transactional
    public Result importSeed(ProfileSeed seed) {
        // 1. User: keyed by email, so a second run updates the same row.
        Long userId = jdbc.queryForObject(
                """
                INSERT INTO app_user (email, daily_apply_cap) VALUES (?, ?)
                ON CONFLICT (email) DO UPDATE SET daily_apply_cap = EXCLUDED.daily_apply_cap
                RETURNING id
                """,
                Long.class,
                seed.email(),
                seed.dailyApplyCap());

        // 2. Preferences: one row per user. Once the user has saved them in the dashboard (onboarded)
        //    the seed no longer overwrites the search preferences; the CV header and graduation date still follow the seed.
        ProfileSeed.Preferences prefs = seed.preferences();
        jdbc.update(
                """
                INSERT INTO profile (user_id, roles, cities, remote, job_types, graduation_date, cv_static)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (user_id) DO UPDATE SET
                    roles = CASE WHEN profile.onboarded THEN profile.roles ELSE EXCLUDED.roles END,
                    cities = CASE WHEN profile.onboarded THEN profile.cities ELSE EXCLUDED.cities END,
                    remote = CASE WHEN profile.onboarded THEN profile.remote ELSE EXCLUDED.remote END,
                    job_types = CASE WHEN profile.onboarded THEN profile.job_types ELSE EXCLUDED.job_types END,
                    graduation_date = EXCLUDED.graduation_date,
                    cv_static = EXCLUDED.cv_static
                """,
                userId,
                textArray(prefs.roles()),
                textArray(prefs.cities()),
                prefs.remote(),
                textArray(prefs.jobTypes()),
                prefs.graduationDate() == null ? null : Date.valueOf(prefs.graduationDate()),
                seed.cv() == null ? null : json.writeValueAsString(seed.cv()));

        // 3. Entries and their bullets. Entries are keyed by slug, so a re-run updates them in
        //    place; bullets are inserted only when the user does not have them yet.
        int inserted = 0;
        int present = 0;
        int sortOrder = 0;
        for (ProfileSeed.Entry entry : seed.entries()) {
            Long entryId = jdbc.queryForObject(
                    """
                    INSERT INTO cv_entry (user_id, section, slug, title, dates, context, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (user_id, slug) DO UPDATE SET
                        section = EXCLUDED.section, title = EXCLUDED.title, dates = EXCLUDED.dates,
                        context = EXCLUDED.context, sort_order = EXCLUDED.sort_order
                    RETURNING id
                    """,
                    Long.class,
                    userId, entry.section(), entrySlug(entry), entry.title(), entry.dates(), entry.context(), sortOrder++);

            for (ProfileSeed.Bullet bullet : entry.bullets()) {
                int rows = jdbc.update(
                        """
                        INSERT INTO bullet (user_id, section, text, tags, entry_id)
                        SELECT ?, ?, ?, ?, ?
                        WHERE NOT EXISTS (
                            SELECT 1 FROM bullet WHERE user_id = ? AND section = ? AND text = ?)
                        """,
                        userId, entry.section(), bullet.text(), textArray(tagsFor(entry, bullet)), entryId,
                        userId, entry.section(), bullet.text());
                if (rows == 1) {
                    inserted++;
                } else {
                    present++;
                    // Bullets imported before entries existed are linked now; their ID is unchanged.
                    jdbc.update(
                            "UPDATE bullet SET entry_id = ? WHERE user_id = ? AND section = ? AND text = ?",
                            entryId, userId, entry.section(), bullet.text());
                }
            }
        }
        return new Result(userId, inserted, present);
    }

    /**
     * Tags stored on a bullet: the entry's technologies, an {@code entry:<slug>} tag that keeps
     * bullets of the same job or project together, and {@code verify} when flagged.
     */
    static List<String> tagsFor(ProfileSeed.Entry entry, ProfileSeed.Bullet bullet) {
        List<String> tags = new ArrayList<>(entry.tags() == null ? List.of() : entry.tags());
        tags.add(ENTRY_TAG_PREFIX + entrySlug(entry));
        if (bullet.verify()) {
            tags.add(VERIFY_TAG);
        }
        return tags;
    }

    /** Two stints at the same employer share a title, so the dates are part of the slug. */
    static String entrySlug(ProfileSeed.Entry entry) {
        return slug(entry.title() + (entry.dates() == null ? "" : " " + entry.dates()));
    }

    /** Lower-case ASCII slug: "e&amp; (Etisalat) Sep 2026" becomes "e-etisalat-sep-2026". */
    static String slug(String value) {
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("[^\\p{ASCII}]", " ");
        return ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    }

    /** Postgres {@code text[]} value; a null list is stored as an empty array. */
    private Array textArray(List<String> values) {
        Object[] items = values == null ? new Object[0] : values.toArray();
        return jdbc.execute((ConnectionCallback<Array>) connection -> connection.createArrayOf("text", items));
    }
}
