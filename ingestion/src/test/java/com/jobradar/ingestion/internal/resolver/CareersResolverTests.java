package com.jobradar.ingestion.internal.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobradar.ingestion.internal.source.FetchResponse;
import com.jobradar.ingestion.internal.source.Fixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The decision per careers URL. The pages here are minimal hand-written HTML, not recordings. */
class CareersResolverTests {

    private static final String PAGE = "https://acme.example/careers";
    private static final String ROBOTS = "https://acme.example/robots.txt";

    private final Fixtures.FakeHttp http = new Fixtures.FakeHttp();
    // The repository is only used by resolvePending(), which the worker's integration test covers.
    private final CareersResolver resolver = new CareersResolver(
            null, http, Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void aCareersUrlThatIsAlreadyAnAtsBoardNeedsNoRequest() {
        assertThat(resolver.resolve("https://jobs.lever.co/Bosta")).contains(new AtsBoard("lever", "Bosta"));
        assertThat(http.requested).isEmpty();
    }

    @Test
    void detectsTheAtsFromTheCareersPage() {
        http.on(ROBOTS, new FetchResponse(404, "", null))
                .on(PAGE, FetchResponse.ok("<a href=\"https://apply.workable.com/acme/j/1A2B3C/\">Backend Engineer</a>"));

        assertThat(resolver.resolve(PAGE)).contains(new AtsBoard("workable", "acme"));
        assertThat(http.requested).containsExactly(ROBOTS, PAGE);
    }

    @Test
    void aReadablePageWithNoKnownAtsIsACustomSite() {
        http.on(ROBOTS, FetchResponse.ok("User-agent: *\nDisallow: /admin\n"))
                .on(PAGE, FetchResponse.ok("<h1>Join us</h1><a href=\"/careers/backend\">Backend Engineer</a>"));

        assertThat(resolver.resolve(PAGE)).contains(AtsBoard.EMAIL_ALERTS);
    }

    @Test
    void robotsTxtIsHonoured() {
        http.on(ROBOTS, FetchResponse.ok("User-agent: *\nDisallow: /careers\n"));

        assertThat(resolver.resolve(PAGE)).isEmpty();
        // The careers page itself was never requested.
        assertThat(http.requested).containsExactly(ROBOTS);
    }

    @Test
    void anUnreadablePageStaysUnresolvedInsteadOfBeingMarkedCustom() {
        http.on(ROBOTS, new FetchResponse(404, "", null)).on(PAGE, new FetchResponse(503, "", null));
        assertThat(resolver.resolve(PAGE)).isEmpty();

        // No answer at all (the fake throws for an unknown URL, as a timeout would).
        assertThat(resolver.resolve("https://down.example/careers")).isEmpty();
    }
}
