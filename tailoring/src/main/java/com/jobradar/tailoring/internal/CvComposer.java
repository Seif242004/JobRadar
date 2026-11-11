package com.jobradar.tailoring.internal;

import com.jobradar.tailoring.internal.CvDocument.Entry;
import com.jobradar.tailoring.internal.CvDocument.Section;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the CV document from the user's stored data and a validated list of bullet IDs.
 * Every string in the result is copied from a database row; nothing is generated.
 */
public final class CvComposer {

    private CvComposer() {}

    /**
     * Section order: Education, Experience, Projects, Technical Skills, Languages. Entries keep
     * the order of the master CV; bullets inside an entry keep the selection's relevance order.
     */
    public static CvDocument compose(TailoringData data, List<Long> selectedIds) {
        return compose(data, selectedIds, null);
    }

    /** @param summary the chosen summary line, or null for none */
    public static CvDocument compose(TailoringData data, List<Long> selectedIds, String summary) {
        Map<Long, ProfileBullet> byId =
                data.bullets().stream().collect(Collectors.toMap(ProfileBullet::id, Function.identity()));
        List<ProfileBullet> selected = selectedIds.stream().map(byId::get).toList();
        CvStatic fixed = data.cvStatic();

        List<Section> sections = new ArrayList<>();
        if (fixed.education() != null && !fixed.education().isEmpty()) {
            sections.add(Section.entries("Education", fixed.education().stream()
                    .map(e -> new Entry(e.title(), e.dates(), e.context(), List.of()))
                    .toList()));
        }
        addEntries(sections, "Experience", "experience", data, selected);
        addEntries(sections, "Projects", "project", data, selected);

        List<String> skillRows = selected.stream()
                .filter(bullet -> bullet.section().equals("skill"))
                // Skill rows read best in the master CV's order, not in relevance order.
                .sorted(Comparator.comparingLong(ProfileBullet::id))
                .map(ProfileBullet::text)
                .toList();
        if (!skillRows.isEmpty()) {
            sections.add(Section.skills("Technical Skills", skillRows));
        }
        if (fixed.languages() != null && !fixed.languages().isBlank()) {
            sections.add(Section.lines("Languages", List.of(fixed.languages())));
        }

        List<CvDocument.Contact> contacts = fixed.contacts() == null
                ? List.of()
                : fixed.contacts().stream().map(c -> new CvDocument.Contact(c.text(), c.url())).toList();
        return new CvDocument(fixed.name(), fixed.headline(), summary, contacts, sections);
    }

    private static void addEntries(
            List<Section> sections, String title, String section, TailoringData data, List<ProfileBullet> selected) {
        // Group the selected bullets of this section by entry, keeping relevance order per entry.
        Map<Long, List<String>> bulletsByEntry = new LinkedHashMap<>();
        for (ProfileBullet bullet : selected) {
            if (bullet.section().equals(section) && bullet.entryId() != null) {
                bulletsByEntry.computeIfAbsent(bullet.entryId(), id -> new ArrayList<>()).add(bullet.text());
            }
        }
        List<Entry> entries = data.entries().stream()
                .filter(entry -> bulletsByEntry.containsKey(entry.id()))
                .sorted(Comparator.comparingInt(CvEntry::sortOrder))
                .map(entry -> new Entry(entry.title(), entry.dates(), entry.context(), bulletsByEntry.get(entry.id())))
                .toList();
        if (!entries.isEmpty()) {
            sections.add(Section.entries(title, entries));
        }
    }

    /** All text of the document, one item per line; the input of the ATS keyword score. */
    public static String plainText(CvDocument document) {
        StringBuilder text = new StringBuilder();
        if (document.summary() != null) {
            text.append(document.summary()).append('\n');
        }
        for (Section section : document.sections()) {
            for (Entry entry : section.entries()) {
                text.append(entry.title()).append('\n');
                if (entry.context() != null) {
                    text.append(entry.context()).append('\n');
                }
                entry.bullets().forEach(bullet -> text.append(bullet).append('\n'));
            }
            section.rows().forEach(row -> text.append(row).append('\n'));
            section.lines().forEach(line -> text.append(line).append('\n'));
        }
        return text.toString();
    }
}
