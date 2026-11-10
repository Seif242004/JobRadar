package com.jobradar.apply.internal;

/**
 * The short note that goes with the CV. A fixed template filled with the posting's title and
 * company and the user's own name: nothing is generated, so nothing can be invented.
 */
public final class EmailComposer {

    private EmailComposer() {}

    public static OutgoingEmail compose(ApplyContext context, String recipient, byte[] pdf) {
        String title = oneLine(context.postingTitle());
        String company = context.company() == null ? null : oneLine(context.company());
        String name = context.name() == null ? "" : oneLine(context.name());

        String position = company == null || company.isBlank() ? title : title + " at " + company;
        String body = "Dear Hiring Team,\n\n"
                + "I am writing to apply for the " + position + " position. My CV is attached.\n\n"
                + "Thank you for your time and consideration.\n\n"
                + "Best regards,\n" + name + "\n" + context.userEmail() + "\n";
        String subject = "Application: " + title + (name.isBlank() ? "" : " - " + name);
        String fileName = (name.isBlank() ? "CV" : name.replaceAll("[^\\p{L}\\p{N}]+", "_") + "_CV") + ".pdf";
        return new OutgoingEmail(context.userEmail(), recipient, subject, body, fileName, pdf);
    }

    /** Header values must not contain line breaks, or a posting title could inject headers. */
    static String oneLine(String value) {
        return value.replaceAll("[\\r\\n\\t]+", " ").strip();
    }
}
