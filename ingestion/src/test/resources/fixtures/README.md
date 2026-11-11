# Connector fixtures

Real responses recorded on 2026-11-10 from the public endpoints below. Tests read these files and
never touch the network.

| File | Request |
| --- | --- |
| `greenhouse/gitlab.json`, `greenhouse/canonical.json` | `GET https://boards-api.greenhouse.io/v1/boards/{token}/jobs?content=true` |
| `greenhouse/unknown-board-404.json` | same, for a board token that does not exist (HTTP 404) |
| `lever/bosta.json`, `lever/leverdemo.json` | `GET https://api.lever.co/v0/postings/{token}?mode=json` |
| `lever/unknown-board-404.json` | same, for a board token that does not exist (HTTP 404) |
| `workable/search-egypt.json` | `GET https://jobs.workable.com/api/v1/jobs?location=Egypt` |
| `workable/rate-limit-429.json` | same endpoint, body of the HTTP 429 answer (`Retry-After: 80921`) |
| `remotive/remote-jobs.json` | `GET https://remotive.com/api/remote-jobs` |

The only edit: the `jobs` array (the top-level array for Lever) is cut down to a few entries to
keep the repository small. Every remaining entry is byte-for-byte what the source returned apart
from JSON re-indentation. `remotive/remote-jobs.json` is complete.

### Gmail alert emails (`gmail/*.eml`) — synthetic, unverified against real mail

No real Wuzzuf, Bayt or LinkedIn job-alert email could be obtained: none is published, and the
owner has none to hand. These four files are **hand-written**, each marked with an
`X-JobRadar-Fixture: synthetic` header:

| File | Written from |
| --- | --- |
| `linkedin-alert.eml` | A public write-up that describes LinkedIn alerts as multipart mail listing each job in plain text and in HTML, with `comm/jobs/view/{id}` links carrying tracking parameters (plus the `currentJobId` form). The title / company / location layout is a guess. |
| `wuzzuf-alert.eml` | The shape of Wuzzuf job URLs (`wuzzuf.net/jobs/p/{id}-{slug}`). The card layout, the "Company - Location" line and the tracker link are guesses. |
| `bayt-alert.eml` | A public test of another project that models Bayt links as `bayt.com/en/{country}/jobs/{slug}-{id}/` wrapped in a click tracker, with "Title - Company - Location" link text. That test is itself synthetic. |
| `newsletter-no-jobs.eml` | A non-alert email with a broken HTML part, to pin tolerance. |

The parsers therefore rely on what any alert must contain (a job link, its text, the next short
lines) and skip anything they cannot read. Replace these files with real emails (Gmail: *Show
original*, then remove personal details) to verify the layouts.

### Real alert emails (`gmail/*-real.eml`)

Recorded on 2026-11-11 from a real mailbox (Gmail "Show original"), then sanitised: addresses, the
recipient's name, recruiter names, tracking tokens and query strings are replaced or removed, and
inline CSS is dropped. Job titles, companies, locations and link paths are unchanged.

| File | What it is |
| --- | --- |
| `linkedin-real.eml` | LinkedIn job alert, plain-text part only (the HTML part is about 100 KB of markup and is not kept). Four jobs; two share a title but are different jobs. Links are `linkedin.com/comm/jobs/view/{id}/`. |
| `wuzzuf-real.eml` | Wuzzuf "recommended jobs" email, HTML. Links are wrapped in an AWS SES click tracker that carries the destination in the **path** (`awstrack.me/L0/{percent-encoded url}/1/{token}`); the company line is "Company - City, Country - Posted N days ago". Shortened to one job. |

No Bayt alert email was found in the mailbox, so `bayt-alert.eml` above is still synthetic.
