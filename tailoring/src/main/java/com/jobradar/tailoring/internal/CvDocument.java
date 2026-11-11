package com.jobradar.tailoring.internal;

import java.util.List;

/**
 * The CV as the Typst template reads it from {@code cv.json}. Field names are part of the
 * template contract (see {@code typst/cv.typ}).
 */
public record CvDocument(String name, String headline, List<Contact> contacts, List<Section> sections) {

    public record Contact(String text, String url) {}

    /**
     * @param kind {@code entries} (headed blocks with bullets), {@code skills} (rows of
     *             "Category: items") or {@code lines} (plain lines)
     */
    public record Section(String title, String kind, List<Entry> entries, List<String> rows, List<String> lines) {

        static Section entries(String title, List<Entry> entries) {
            return new Section(title, "entries", entries, List.of(), List.of());
        }

        static Section skills(String title, List<String> rows) {
            return new Section(title, "skills", List.of(), rows, List.of());
        }

        static Section lines(String title, List<String> lines) {
            return new Section(title, "lines", List.of(), List.of(), lines);
        }
    }

    public record Entry(String title, String dates, String context, List<String> bullets) {}
}
