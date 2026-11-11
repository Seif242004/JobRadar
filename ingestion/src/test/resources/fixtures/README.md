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

## Synthetic fixtures (not recordings)

| File | Why it is synthetic |
| --- | --- |
| `workable/board-synthetic.json`, `workable/board-empty-synthetic.json` | `GET https://apply.workable.com/api/v1/widget/accounts/{token}?details=true` answered HTTP 429 (Cloudflare 1015, `Retry-After` of hours) from the build environment on 2026-11-10 and 2026-11-11, so no real response could be recorded. These files are hand-written from the field list that public integrations document: `title`, `shortcode`, `shortlink`, `url`, `department`, `city`, `state`, `country`, `telecommuting`, `published_on`, `description`, with one entry per location for a posting open in several countries. **Unverified against the live API.** Replace them with recordings of a real board (for example `moneyfellows`) as soon as the endpoint is reachable. |
