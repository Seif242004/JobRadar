package com.jobradar.cvscore;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ATS keyword score: the share of the posting's keywords that appear in the CV, 0 to 100.
 *
 * <p>Keywords are taken from the posting alone, never from the profile, so terms the candidate
 * lacks lower the score instead of disappearing from it. A keyword is every non-stopword in the
 * title, plus every non-stopword that occurs at least twice in the description, capped at
 * {@link #MAX_KEYWORDS} by frequency.
 */
public final class AtsScorer {

    public static final int MAX_KEYWORDS = 30;

    /** Letters and digits, keeping the characters of names like C++, C#, .NET and Node.js. */
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}+#.]*");

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "has", "have", "in", "is", "it",
            "of", "on", "or", "our", "that", "the", "their", "this", "to", "we", "will", "with", "you", "your",
            "who", "what", "can", "all", "about", "into", "more", "such", "they", "them", "us", "not", "but",
            "if", "its", "other", "work", "working", "team", "teams", "role", "job", "experience", "years",
            "year", "strong", "good", "ability", "skills", "knowledge", "including", "etc", "must", "plus",
            "join", "looking", "company", "candidate", "candidates", "responsibilities", "requirements",
            "required", "preferred", "using", "use", "new", "well", "across", "within", "also", "was",
            // Generic filler that said nothing about skills in real postings.
            "only", "based", "bonus", "borrow", "status", "ensure", "help", "make", "need", "want", "like",
            "get", "just", "any", "etc.", "per", "one", "may", "should", "would", "could", "being", "than",
            "then", "when", "where", "which", "while", "both", "each", "every", "many", "most", "some",
            "very", "high", "great", "love", "day", "time", "part", "full", "free", "open", "real", "best",
            "ideal", "opportunity", "opportunities", "environment", "responsible", "ownership", "impact");

    private AtsScorer() {}

    public static double score(String postingTitle, String postingDescription, String cvText) {
        Set<String> keywords = keywords(postingTitle, postingDescription);
        if (keywords.isEmpty()) {
            return 0.0;
        }
        Set<String> cvTokens = new LinkedHashSet<>(tokens(cvText));
        long covered = keywords.stream().filter(cvTokens::contains).count();
        return Math.round(covered * 1000.0 / keywords.size()) / 10.0;
    }

    /** Posting keywords that the CV text does not contain, most important first. */
    public static List<String> missing(String postingTitle, String postingDescription, String cvText) {
        Set<String> cvTokens = new LinkedHashSet<>(tokens(cvText));
        // Technical terms first: those are what a reader can actually add to a CV.
        return keywords(postingTitle, postingDescription).stream()
                .filter(k -> !cvTokens.contains(k))
                .sorted(java.util.Comparator.comparing((String k) -> !isTechnical(k)))
                .toList();
    }

    private static final Set<String> TECH = Set.of(
            "java", "python", "javascript", "typescript", "react", "node", "node.js", "spring", "sql", "postgresql",
            "mysql", "mongodb", "redis", "docker", "kubernetes", "aws", "azure", "gcp", "linux", "git", "api", "rest",
            "graphql", "c++", "c#", ".net", "golang", "go", "rust", "php", "laravel", "django", "flask", "fastapi",
            "angular", "vue", "next.js", "tailwind", "html", "css", "ci/cd", "devops", "ml", "ai", "llm", "nlp",
            "pytorch", "tensorflow", "pandas", "numpy", "opengl", "unity", "unreal", "roblox", "luau", "kafka",
            "rabbitmq", "microservices", "firebase", "supabase", "n8n", "convex", "terraform", "jenkins", "agile",
            "scrum", "testing", "selenium", "figma", "android", "kotlin", "swift", "flutter");

    private static boolean isTechnical(String keyword) {
        return TECH.contains(keyword) || keyword.matches(".*[+#].*");
    }

    /** Number of keywords taken from the posting; the denominator of the score. */
    public static int keywordCount(String postingTitle, String postingDescription) {
        return keywords(postingTitle, postingDescription).size();
    }

    public static Set<String> keywords(String title, String description) {
        Set<String> keywords = new LinkedHashSet<>(tokens(title));
        Map<String, Integer> counts = new HashMap<>();
        for (String token : tokens(description)) {
            counts.merge(token, 1, Integer::sum);
        }
        counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= 2)
                // Most frequent first; ties alphabetically, so the result is deterministic.
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .takeWhile(token -> keywords.size() < MAX_KEYWORDS)
                .forEach(keywords::add);
        return keywords;
    }

    /** Lower-case tokens without stopwords, single characters or pure numbers. */
    public static List<String> tokens(String text) {
        if (text == null) {
            return List.of();
        }
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        List<String> tokens = new java.util.ArrayList<>();
        while (matcher.find()) {
            // A trailing dot is sentence punctuation, not part of a name like "node.js".
            String token = matcher.group().replaceAll("\\.+$", "");
            boolean keep = token.length() > 1 || token.equals("c");
            if (keep && !STOPWORDS.contains(token) && !token.matches("[\\d.]+")) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
