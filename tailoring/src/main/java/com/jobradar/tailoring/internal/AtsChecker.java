package com.jobradar.tailoring.internal;

import com.jobradar.cvscore.AtsScorer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * What an applicant tracking system sees. The PDF is read back the way a parser reads it (plain
 * text extraction) and checked for a text layer, the contact details, the standard section
 * headings, the page count and the posting's keywords. Nothing here changes the CV.
 */
public final class AtsChecker {

    /** Keyword coverage at or above which the check passes. */
    static final double COVERAGE_OK = 60.0;

    private AtsChecker() {}

    public static AtsReport check(TailoringData data, CvDocument document, byte[] pdf, List<ProfileBullet> profileBullets, List<Long> usedBulletIds) {
        String extracted;
        int pages;
        try (PDDocument pd = Loader.loadPDF(pdf)) {
            pages = pd.getNumberOfPages();
            extracted = new PDFTextStripper().getText(pd);
        } catch (IOException e) {
            throw new UncheckedIOException("Rendered CV is not a readable PDF", e);
        }
        String lower = extracted.toLowerCase(Locale.ROOT);

        // Keyword coverage, measured on what a parser extracts, not on the source text.
        Set<String> keywords = AtsScorer.keywords(data.postingTitle(), data.postingDescription());
        Set<String> cvTokens = Set.copyOf(AtsScorer.tokens(extracted));
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String keyword : keywords) {
            (cvTokens.contains(keyword) ? matched : missing).add(keyword);
        }
        double score = keywords.isEmpty() ? 0.0 : Math.round(matched.size() * 1000.0 / keywords.size()) / 10.0;

        List<AtsReport.Check> checks = new ArrayList<>();
        boolean text = extracted.strip().length() > 200 && document.name() != null && lower.contains(document.name().toLowerCase(Locale.ROOT));
        checks.add(new AtsReport.Check("Selectable text", text,
                text ? "A parser reads " + extracted.strip().length() + " characters, including your name." : "The PDF has no readable text layer."));

        String email = firstEmail(document);
        boolean contact = email != null && lower.contains(email.toLowerCase(Locale.ROOT));
        checks.add(new AtsReport.Check("Contact details", contact,
                contact ? "Your e-mail address is in the text layer." : "No e-mail address was found in the text layer."));

        List<String> headings = document.sections().stream().map(CvDocument.Section::title).toList();
        List<String> lostHeadings = headings.stream().filter(h -> !lower.contains(h.toLowerCase(Locale.ROOT))).toList();
        checks.add(new AtsReport.Check("Standard headings", lostHeadings.isEmpty(),
                lostHeadings.isEmpty() ? String.join(", ", headings) + " are all readable." : "Not found in the text: " + String.join(", ", lostHeadings)));

        checks.add(new AtsReport.Check("One page", pages == 1, pages + (pages == 1 ? " page." : " pages; most recruiters read one.")));

        boolean summary = document.summary() != null && !document.summary().isBlank();
        checks.add(new AtsReport.Check("Role summary", summary,
                summary ? "A summary matched to this role is at the top." : "No confirmed summary yet; confirm one in the dashboard."));

        checks.add(new AtsReport.Check("Keyword coverage", score >= COVERAGE_OK,
                score + "% of the posting's keywords (" + matched.size() + " of " + keywords.size() + ")."));

        return new AtsReport(score, List.copyOf(matched), List.copyOf(missing), List.copyOf(checks),
                fixes(missing, profileBullets, usedBulletIds, checks));
    }

    /** Fixes that stay inside the rules: reuse what the profile already says, never add a claim. */
    private static List<String> fixes(List<String> missing, List<ProfileBullet> profileBullets, List<Long> used, List<AtsReport.Check> checks) {
        List<String> fixes = new ArrayList<>();
        Set<Long> onCv = Set.copyOf(used);
        List<String> inProfile = new ArrayList<>();
        List<String> notInProfile = new ArrayList<>();
        for (String keyword : missing) {
            boolean known = profileBullets.stream()
                    .filter(b -> !b.verify() && !onCv.contains(b.id()))
                    .anyMatch(b -> AtsScorer.tokens(b.text()).contains(keyword));
            (known ? inProfile : notInProfile).add(keyword);
        }
        if (!inProfile.isEmpty()) {
            fixes.add("Your profile already mentions " + String.join(", ", limit(inProfile)) + " in bullets that did not fit on this page. Swap one in if the role cares about it.");
        }
        if (!notInProfile.isEmpty()) {
            fixes.add("Not in your profile: " + String.join(", ", limit(notInProfile)) + ". Add a bullet to your profile only if you have really done it.");
        }
        for (AtsReport.Check check : checks) {
            if (!check.ok() && !check.name().equals("Keyword coverage")) {
                fixes.add(check.name() + ": " + check.detail());
            }
        }
        return fixes;
    }

    private static List<String> limit(List<String> words) {
        return words.size() > 8 ? words.subList(0, 8) : words;
    }

    private static String firstEmail(CvDocument document) {
        return document.contacts().stream()
                .map(CvDocument.Contact::text)
                .filter(t -> t != null && t.contains("@"))
                .findFirst()
                .orElse(null);
    }
}
