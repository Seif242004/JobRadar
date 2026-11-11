package com.jobradar.matching;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Who a posting is for. A posting can be open to several groups ("students and fresh graduates"),
 * so {@link #of} returns every level it names. Read from the title first and from description
 * phrases that state the audience; unrelated mentions of "intern" or "junior" are not counted.
 */
public enum PostingLevel {
    /** Still studying: internships, working students, summer and co-op programs. */
    STUDENT("student"),
    /** No degree needed or not yet graduated: undergraduates, trainees, bootcamp-style programs. */
    NON_GRADUATE("non-graduate"),
    /** Newly graduated: graduate programs and fresh-graduate roles. */
    FRESH_GRAD("fresh-grad"),
    /** Junior role, usually up to two years of experience. */
    JUNIOR("junior");

    private final String id;

    PostingLevel(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    private static final Pattern STUDENT_TITLE = Pattern.compile(
            "\\bintern(ship)?s?\\b|working student|werkstudent|\\bstudents?\\b|summer|winter|\\bco-?op\\b|تدريب|متدرب");
    private static final Pattern STUDENT_TEXT = Pattern.compile(
            "working student|werkstudent|currently (studying|enrolled)|final[- ]year student|undergraduate students?"
                    + "|university students?|\\bstudents?\\b.{0,20}\\b(apply|welcome|eligible)|internship program");
    private static final Pattern NON_GRAD_TITLE = Pattern.compile(
            "\\btrainee\\b|apprentice|\\bundergrad\\w*|bootcamp|\\bacademy\\b|\\btraining program(me)?\\b|\\bmentorship\\b");
    private static final Pattern NON_GRAD_TEXT = Pattern.compile(
            "no degree (is )?(required|needed)|degree (is )?not required|not yet graduated|undergraduates?"
                    + "|\\bno (prior )?experience (is )?(required|needed)");
    private static final Pattern GRAD_TITLE = Pattern.compile(
            "\\bgrad(uate)?s?\\b|\\bfresh(er)?s?\\b|new grad|early career|campus|rotational|حديث[يي] التخرج");
    private static final Pattern GRAD_TEXT = Pattern.compile(
            "fresh(er)?s? graduates?|recent graduates?|new graduates?|graduate (program|role|position|scheme)|حديث[يي] التخرج");
    private static final Pattern JUNIOR_TITLE = Pattern.compile("\\bjunior\\b|\\bjr\\b|entry[- ]level|associate");
    private static final Pattern JUNIOR_TEXT = Pattern.compile("\\bjunior\\b|entry[- ]level|\\b[01]\\s*(?:-|to)\\s*2\\s*years?");

    public static EnumSet<PostingLevel> of(String title, String description) {
        String t = lower(title);
        String d = lower(description);
        EnumSet<PostingLevel> levels = EnumSet.noneOf(PostingLevel.class);
        if (STUDENT_TITLE.matcher(t).find() || STUDENT_TEXT.matcher(d).find()) {
            levels.add(STUDENT);
        }
        if (NON_GRAD_TITLE.matcher(t).find() || NON_GRAD_TEXT.matcher(d).find()) {
            levels.add(NON_GRADUATE);
        }
        if (GRAD_TITLE.matcher(t).find() || GRAD_TEXT.matcher(d).find()) {
            levels.add(FRESH_GRAD);
        }
        if (JUNIOR_TITLE.matcher(t).find() || JUNIOR_TEXT.matcher(d).find()) {
            levels.add(JUNIOR);
        }
        return levels;
    }

    /** Level ids for the dashboard, in a stable order. */
    public static List<String> ids(String title, String description) {
        return of(title, description).stream().map(PostingLevel::id).toList();
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
