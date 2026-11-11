package com.jobradar.profile.seed;

import java.time.LocalDate;
import java.util.List;

/**
 * A master profile as it is written in a seed file (see {@code seed/profile.example.json}).
 *
 * <p>Bullets are grouped by entry (one job, one project, one skill row) so the file stays close
 * to the CV it was written from. The database stores bullets flat; see {@link ProfileSeedImporter}
 * for the mapping.
 */
public record ProfileSeed(
        String email, int dailyApplyCap, Preferences preferences, Cv cv, List<Entry> entries, List<Summary> summaries) {

    /** A seed without summary variants. */
    public ProfileSeed(String email, int dailyApplyCap, Preferences preferences, Cv cv, List<Entry> entries) {
        this(email, dailyApplyCap, preferences, cv, entries, List.of());
    }

    /**
     * One CV summary line for a kind of role. Imported as unconfirmed: it reaches a CV only after
     * the user confirms it in the dashboard.
     *
     * @param focus words of a posting that select this variant
     */
    public record Summary(String label, List<String> focus, String text) {}


    /**
     * Fixed parts of the CV, stored as JSON in {@code profile.cv_static}. May be null in a seed
     * file; tailoring then cannot render a CV for the user.
     *
     * @param headline  one line under the contacts, for example the degree; may be null
     * @param languages one line, for example "Arabic (Native), English (Fluent)"; may be null
     */
    public record Cv(String name, String headline, List<Contact> contacts, List<Education> education, String languages) {}

    /** @param url link target, or null for plain text such as the city */
    public record Contact(String text, String url) {}

    public record Education(String title, String dates, String context) {}

    /** Maps one to one onto the {@code profile} table. */
    public record Preferences(
            List<String> roles, List<String> cities, boolean remote, List<String> jobTypes, LocalDate graduationDate) {}

    /**
     * @param section one of {@code experience}, {@code project}, {@code skill}
     * @param title   entry heading, for example "Software Engineering Intern · e&amp; (Etisalat)"
     * @param dates   free text as printed on the CV; may be null
     * @param context sub-heading line under the title; may be null
     * @param tags    technologies of the entry; copied onto each of its bullets
     */
    public record Entry(
            String section, String title, String dates, String context, List<String> tags, List<Bullet> bullets) {}

    /**
     * @param text   the bullet exactly as it may appear on a CV
     * @param verify true when the user still has to confirm the wording; such bullets get the
     *               {@code verify} tag so tailoring can leave them out
     */
    public record Bullet(String text, boolean verify) {}
}
