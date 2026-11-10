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
