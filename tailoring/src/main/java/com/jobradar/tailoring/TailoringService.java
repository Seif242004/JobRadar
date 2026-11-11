package com.jobradar.tailoring;

import com.jobradar.tailoring.internal.AtsChecker;
import com.jobradar.tailoring.internal.AtsReport;
import com.jobradar.tailoring.internal.AtsScorer;
import com.jobradar.tailoring.internal.CvSummary;
import com.jobradar.tailoring.internal.NoInvention;
import com.jobradar.tailoring.internal.SummaryPicker;
import com.jobradar.tailoring.internal.BulletSelector;
import com.jobradar.tailoring.internal.CvComposer;
import com.jobradar.tailoring.internal.CvStore;
import com.jobradar.tailoring.internal.OnePageFitter;
import com.jobradar.tailoring.internal.ProfileBullet;
import com.jobradar.tailoring.internal.TailoringData;
import com.jobradar.tailoring.internal.TailoringRepository;
import com.jobradar.tailoring.internal.TailoringValidator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Flow 3 of docs/PLAN.md: select bullets, validate, render, fit to one page, score, store.
 *
 * <p>The no-invention guarantee is structural. The LLM is only ever asked for bullet IDs; the
 * validator rejects any ID that is not one of the user's confirmed bullets; and the CV text is
 * assembled from database rows alone. No model output is ever written into a CV.
 */
@Service
public class TailoringService {

    private static final Logger log = LoggerFactory.getLogger(TailoringService.class);

    private final TailoringRepository repository;
    private final BulletSelector selector;
    private final OnePageFitter fitter;
    private final CvStore store;

    TailoringService(TailoringRepository repository, BulletSelector selector, OnePageFitter fitter, CvStore store) {
        this.repository = repository;
        this.selector = selector;
        this.fitter = fitter;
        this.store = store;
    }

    /**
     * @return the stored CV, or empty when the user or posting no longer exists, or the user has
     *         no CV data or no usable bullets
     */
    public Optional<CvReady> tailor(long userId, long postingId) {
        // Idempotent: a redelivered request returns the CV that already exists.
        Optional<CvReady> existing = repository.findCvVersion(userId, postingId);
        if (existing.isPresent()) {
            return existing;
        }
        Optional<TailoringData> loaded = repository.load(userId, postingId);
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        TailoringData data = loaded.get();
        List<ProfileBullet> selectable = data.selectableBullets();
        if (selectable.isEmpty()) {
            log.info("User {} has no confirmed bullets, no CV rendered", userId);
            return Optional.empty();
        }

        // 1. Ranked bullet IDs: from the LLM, or from embedding order when it is unavailable.
        List<Long> ranked = selector.select(data).orElseGet(() -> repository.rankByEmbedding(userId, postingId));

        // 2. Validate. A rejected selection is never repaired by guessing: the deterministic
        //    embedding order replaces it entirely.
        TailoringValidator.Result check = TailoringValidator.validate(ranked, selectable);
        if (!check.valid()) {
            log.warn("Bullet selection for user {} posting {} rejected: {}", userId, postingId, check.problem());
            ranked = repository.rankByEmbedding(userId, postingId);
            TailoringValidator.Result fallback = TailoringValidator.validate(ranked, selectable);
            if (!fallback.valid()) {
                throw new IllegalStateException("Fallback selection is invalid: " + fallback.problem());
            }
        }

        // 2b. Summary line: a confirmed variant chosen by keyword overlap, and only if it passes the
        //     no-invention guard against the user's own CV text.
        String summary = SummaryPicker.pick(data.summaries(), data.postingTitle(), data.postingDescription())
                .map(CvSummary::text)
                .filter(text -> {
                    var unknown = NoInvention.unknownTerms(text, corpus(data));
                    if (!unknown.isEmpty()) {
                        log.warn("Summary skipped for user {}: terms not in the CV: {}", userId, unknown);
                    }
                    return unknown.isEmpty();
                })
                .orElse(null);

        // 3. Render; drop the lowest-ranked bullet until the CV is exactly one page.
        OnePageFitter.Fitted fitted = fitter.fit(data, ranked, summary);

        // 4. ATS check on the PDF as a parser reads it: keyword coverage plus structural checks.
        AtsReport report = AtsChecker.check(data, fitted.document(), fitted.pdf(), data.bullets(), fitted.bulletIds());
        double atsScore = report.score();

        // 5. Store the PDF, then the row that points at it.
        String key = store.put(userId, postingId, fitted.pdf());
        long cvVersionId = repository.insertCvVersion(userId, postingId, fitted.bulletIds(), key, atsScore, summary, report);
        return Optional.of(new CvReady(userId, postingId, cvVersionId, atsScore));
    }

    /** Every piece of CV text the user has written: the only vocabulary a summary may draw on. */
    private static String corpus(TailoringData data) {
        StringBuilder text = new StringBuilder();
        data.bullets().forEach(bullet -> text.append(bullet.text()).append('\n'));
        data.entries().forEach(entry -> text.append(entry.title()).append(' ').append(entry.dates()).append(' ').append(entry.context()).append('\n'));
        var fixed = data.cvStatic();
        text.append(fixed.name()).append(' ').append(fixed.headline()).append(' ').append(fixed.languages()).append('\n');
        if (fixed.education() != null) {
            fixed.education().forEach(e -> text.append(e.title()).append(' ').append(e.dates()).append(' ').append(e.context()).append('\n'));
        }
        return text.toString();
    }
}
