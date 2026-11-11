package com.jobradar.dashboard;

import com.jobradar.matching.MatchingApi;
import com.jobradar.notifier.CvLinkSigner;
import com.jobradar.cvscore.BaseCvs;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Posting detail, tailored CV files, the ATS report and the summary variants. The PDF is streamed
 * through the api, because the bucket is private and a presigned LocalStack URL does not open on a
 * phone.
 */
@RestController
@Profile({"local-dashboard", "dashboard"})
@RequestMapping("/api/dashboard")
class CvController {

    record CvInfo(long id, double atsScore, String summary, JsonNode atsReport, String pdfUrl) {}

    record PostingDetail(
            long postingId, String title, String company, String location, String description, String band,
            Double fit, String reasons, String applyChannel, String applyTarget, CvInfo cv,
            List<BaseCvs.Score> baseScores, boolean thin, String enrichStatus) {}

    record SummaryRow(long id, String label, String text, boolean confirmed, List<String> focus) {}

    record Confirm(boolean confirmed) {}

    private static final String PDF_KEY_PREFIX = "cv/";

    private final JdbcTemplate jdbc;
    private final S3Client s3;
    private final JsonMapper json;
    private final CvLinkSigner signer;
    private final MatchingApi matcher;
    private final BaseCvs baseCvs;
    private final String bucket;

    CvController(
            JdbcTemplate jdbc, S3Client s3, JsonMapper json, CvLinkSigner signer, MatchingApi matcher, BaseCvs baseCvs,
            @Value("${jobradar.notifier.cv-bucket}") String bucket) {
        this.jdbc = jdbc;
        this.s3 = s3;
        this.json = json;
        this.signer = signer;
        this.matcher = matcher;
        this.baseCvs = baseCvs;
        this.bucket = bucket;
    }

    private Optional<Long> userId() {
        return jdbc.queryForList("SELECT id FROM app_user ORDER BY id LIMIT 1", Long.class).stream().findFirst();
    }

    @GetMapping("/postings/{id}")
    ResponseEntity<PostingDetail> posting(@PathVariable long id) {
        Optional<Long> user = userId();
        if (user.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return jdbc.query(
                        """
                        SELECT p.id, p.title, c.name AS company, p.location, p.description, p.apply_channel, p.apply_target,
                               m.band, m.fit_score, m.reasons, p.enrich_status
                        FROM posting p
                        LEFT JOIN company c ON c.id = p.company_id
                        LEFT JOIN match m ON m.posting_id = p.id AND m.user_id = ?
                        WHERE p.id = ?
                        """,
                        (rs, n) -> new PostingDetail(
                                rs.getLong("id"), rs.getString("title"), rs.getString("company"), rs.getString("location"),
                                rs.getString("description"), rs.getString("band"),
                                rs.getBigDecimal("fit_score") == null ? null : rs.getBigDecimal("fit_score").doubleValue(),
                                rs.getString("reasons"), rs.getString("apply_channel"), rs.getString("apply_target"),
                                cv(user.get(), id), List.of(),
                                rs.getString("description") == null || rs.getString("description").length() < 600,
                                rs.getString("enrich_status")),
                        user.get(), id)
                .stream()
                .findFirst()
                .map(d -> new PostingDetail(
                        d.postingId(), d.title(), d.company(), d.location(), d.description(), d.band(), d.fit(),
                        d.reasons(), d.applyChannel(), d.applyTarget(), d.cv(),
                        baseCvs.score(user.get(), d.title(), d.description()), d.thin(), d.enrichStatus()))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/base-cvs")
    List<BaseCvs.Info> baseCvList() {
        return userId().map(baseCvs::list).orElse(List.of());
    }

    /** Stores the uploaded PDF unchanged under its own file name. */
    @PostMapping("/base-cvs")
    ResponseEntity<?> baseCvUpload(@RequestParam("file") MultipartFile file, @RequestParam("label") String label) {
        long user = userId().orElseThrow(() -> new IllegalStateException("No user yet"));
        String name = file.getOriginalFilename() == null ? "cv.pdf" : file.getOriginalFilename();
        try {
            return ResponseEntity.ok(baseCvs.save(user, label.strip(), name, file.getBytes()));
        } catch (IllegalArgumentException | java.io.IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/base-cvs/{id}/file")
    ResponseEntity<byte[]> baseCvFile(@PathVariable long id) {
        long user = userId().orElse(-1L);
        return baseCvs.pdf(user, id)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "inline; filename=\"" + baseCvs.info(user, id).map(BaseCvs.Info::filename).orElse("cv.pdf") + "\"")
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/base-cvs/{id}")
    ResponseEntity<Void> baseCvDelete(@PathVariable long id) {
        return userId().filter(u -> baseCvs.delete(u, id)).isPresent()
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private CvInfo cv(long userId, long postingId) {
        return jdbc.query(
                        "SELECT id, ats_score, summary, ats_report::text AS report FROM cv_version WHERE user_id = ? AND posting_id = ? ORDER BY id DESC LIMIT 1",
                        (rs, n) -> new CvInfo(
                                rs.getLong("id"), rs.getDouble("ats_score"), rs.getString("summary"),
                                rs.getString("report") == null ? null : json.readTree(rs.getString("report")),
                                "/api/dashboard/postings/" + postingId + "/cv.pdf"),
                        userId, postingId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    /** The newest CV of the posting, for the signed-in dashboard user. */
    @GetMapping("/postings/{id}/cv.pdf")
    ResponseEntity<byte[]> pdf(@PathVariable long id) {
        Optional<String> key = userId().flatMap(user -> jdbc.queryForList(
                        "SELECT pdf_s3_key FROM cv_version WHERE user_id = ? AND posting_id = ? ORDER BY id DESC LIMIT 1",
                        String.class, user, id).stream().findFirst());
        return key.map(this::stream).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * A CV by its storage key, for the signed links in notifications. The signature binds the key
     * and an expiry, so the link opens that one file and nothing else.
     */
    @GetMapping("/cv")
    ResponseEntity<byte[]> signed(@RequestParam("k") String key, @RequestParam("t") String token) {
        if (!key.startsWith(PDF_KEY_PREFIX) || key.contains("..") || !signer.verify(key, token)) {
            return ResponseEntity.status(403).build();
        }
        return stream(key);
    }

    private ResponseEntity<byte[]> stream(String key) {
        try {
            byte[] bytes = s3.getObject(r -> r.bucket(bucket).key(key), ResponseTransformer.toBytes()).asByteArray();
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"cv.pdf\"")
                    .cacheControl(CacheControl.noStore())
                    .body(bytes);
        } catch (NoSuchKeyException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Drops the stored CV of a posting and asks tailoring for a new one, for example after a
     * summary was confirmed. Refused when an application already used that CV.
     */
    @PostMapping("/postings/{id}/cv/rebuild")
    ResponseEntity<Map<String, String>> rebuild(@PathVariable long id) {
        long user = userId().orElseThrow(() -> new IllegalStateException("No user yet"));
        int deleted = jdbc.update(
                """
                DELETE FROM cv_version v WHERE v.user_id = ? AND v.posting_id = ?
                  AND NOT EXISTS (SELECT 1 FROM application a WHERE a.cv_version_id = v.id)
                """,
                user, id);
        if (deleted == 0 && cv(user, id) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "This CV was already used for an application and is kept as sent."));
        }
        matcher.requestTailor(user, id);
        return ResponseEntity.accepted().body(Map.of("status", "queued"));
    }

    @GetMapping("/summaries")
    List<SummaryRow> summaries() {
        return userId()
                .map(user -> jdbc.query(
                        "SELECT id, label, text, verify, focus FROM cv_summary WHERE user_id = ? ORDER BY sort_order, id",
                        (rs, n) -> new SummaryRow(
                                rs.getLong("id"), rs.getString("label"), rs.getString("text"), !rs.getBoolean("verify"),
                                List.of((String[]) rs.getArray("focus").getArray())),
                        user))
                .orElse(List.of());
    }

    /** Confirming says "this wording is true for me"; only confirmed summaries ever reach a CV. */
    @PostMapping("/summaries/{id}/confirm")
    ResponseEntity<Void> confirm(@PathVariable long id, @RequestBody Confirm body) {
        long user = userId().orElseThrow(() -> new IllegalStateException("No user yet"));
        int rows = jdbc.update("UPDATE cv_summary SET verify = ? WHERE id = ? AND user_id = ?", !body.confirmed(), id, user);
        return rows == 1 ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
