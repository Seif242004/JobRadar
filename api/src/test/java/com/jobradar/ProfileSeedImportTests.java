package com.jobradar;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.profile.seed.ProfileSeed;
import com.jobradar.profile.seed.ProfileSeedImporter;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The master profile import: bullets get stable IDs and re-running it changes nothing. */
class ProfileSeedImportTests extends PostgresIntegrationTest {

    @Autowired
    ProfileSeedImporter importer;

    @Autowired
    JdbcTemplate jdbc;

    private static ProfileSeed seed(String email, List<ProfileSeed.Bullet> internBullets) {
        return new ProfileSeed(
                email,
                5,
                new ProfileSeed.Preferences(
                        List.of("Backend Engineer"), List.of("Cairo"), true,
                        List.of("internship"), LocalDate.of(2027, 6, 30)),
                new ProfileSeed.Cv(
                        "Test Person", "B.Sc. Computer Engineering",
                        List.of(new ProfileSeed.Contact("Cairo, Egypt", null)),
                        List.of(new ProfileSeed.Education("B.Sc. · Example University", "2022 – Present", null)),
                        "Arabic (Native)"),
                List.of(
                        new ProfileSeed.Entry(
                                "experience", "Intern · Example Co", "Jul 2026 – Aug 2026", null,
                                List.of("Spring Boot"), internBullets),
                        new ProfileSeed.Entry(
                                "skill", "Languages", null, null, List.of("Java", "SQL"),
                                List.of(new ProfileSeed.Bullet("Languages: Java, SQL", false)))));
    }

    @Test
    void importsUserPreferencesAndBulletsWithTags() {
        ProfileSeedImporter.Result result = importer.importSeed(seed(
                "import@example.com",
                List.of(new ProfileSeed.Bullet("Built 15+ REST services.", false),
                        new ProfileSeed.Bullet("Unconfirmed claim.", true))));

        assertThat(result.bulletsInserted()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT graduation_date::text FROM profile WHERE user_id = ?", String.class, result.userId()))
                .isEqualTo("2027-06-30");

        // Entry technologies, the entry slug (title + dates) and the verify flag all land in tags.
        String tags = jdbc.queryForObject(
                "SELECT array_to_string(tags, '|') FROM bullet WHERE user_id = ? AND text = 'Unconfirmed claim.'",
                String.class, result.userId());
        assertThat(tags).isEqualTo("Spring Boot|entry:intern-example-co-jul-2026-aug-2026|verify");

        // Entry heading and dates are stored once and every bullet points at its entry.
        assertThat(jdbc.queryForObject(
                        """
                        SELECT e.title || ' / ' || e.dates FROM bullet b JOIN cv_entry e ON e.id = b.entry_id
                        WHERE b.user_id = ? AND b.text = 'Built 15+ REST services.'
                        """,
                        String.class, result.userId()))
                .isEqualTo("Intern · Example Co / Jul 2026 – Aug 2026");
        assertThat(jdbc.queryForObject(
                        "SELECT cv_static ->> 'name' FROM profile WHERE user_id = ?", String.class, result.userId()))
                .isEqualTo("Test Person");
    }

    @Test
    void reimportKeepsBulletIdsAndOnlyAddsNewBullets() {
        List<ProfileSeed.Bullet> first = List.of(new ProfileSeed.Bullet("Built 15+ REST services.", false));
        long userId = importer.importSeed(seed("reimport@example.com", first)).userId();
        List<Long> idsBefore = bulletIds(userId);

        // Second run: same bullets plus one new one.
        ProfileSeedImporter.Result second = importer.importSeed(seed(
                "reimport@example.com",
                List.of(first.get(0), new ProfileSeed.Bullet("Integrated Stripe.", false))));

        assertThat(second.userId()).isEqualTo(userId);
        assertThat(second.bulletsInserted()).isEqualTo(1);
        assertThat(second.bulletsAlreadyPresent()).isEqualTo(2);
        assertThat(bulletIds(userId)).startsWith(idsBefore.toArray(Long[]::new)).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cv_entry WHERE user_id = ?", Long.class, userId))
                .isEqualTo(2);
    }

    private List<Long> bulletIds(long userId) {
        return jdbc.queryForList("SELECT id FROM bullet WHERE user_id = ? ORDER BY id", Long.class, userId);
    }
}
