# JobRadar — SPEC (v0.2)

Source of truth for research and architecture: `docs/PLAN.md`. This spec adds only what the plan
leaves open and fixes the contracts between modules. Where the two disagree, the plan wins until
Seif says otherwise.

## 1. Decisions

Seif accepted the recommended defaults on 2026-11-10 ("same LLM provider as fitcrew, defaults for
the rest"). All of them are configuration, so any can be changed later without a redesign.

| # | Decision | Value |
| --- | --- | --- |
| D1 | LLM provider (rerank + tailor) | Same as fitcrew: NVIDIA Build, OpenAI-compatible endpoint `https://integrate.api.nvidia.com/v1`, default model `openai/gpt-oss-20b`, with an ordered fallback list when a model ID is retired (404). Config: `JOBRADAR_AI_BASE_URL`, `JOBRADAR_AI_MODEL`, `JOBRADAR_AI_KEY`. Called over plain HTTP behind `LlmClient`; no provider SDK |
| D2 | Monthly LLM budget cap | 0 USD: free endpoints only. The cap is enforced as a daily call limit (default 200 calls/day); when reached, rerank and tailoring stop and matching falls back to embedding score only |
| D3 | Embedding model and dimension | **Changed 2026-11-10:** `baai/bge-m3` is not in NVIDIA Build's `GET /v1/models` list. Default is now `nvidia/llama-3.2-nv-embedqa-1b-v1` (multilingual, Matryoshka output sizes) requested with `dimensions=1024` and `input_type=passage`, so **`vector(1024)`** and the V1 schema stay as they are. Not yet verified with a real key: if the endpoint rejects `dimensions`, pick another model in `JOBRADAR_AI_EMBEDDING_MODEL`; a model with a different size needs a migration. Behind `EmbeddingClient` |
| D4 | PDF renderer | Typst |
| D5 | AWS region | eu-central-1 |
| D6 | ATS poll interval | 15 min |
| D7 | Push thresholds | Hot band = age < 2 h and fit >= 80 (hot queue, immediate tailoring). **Apply now** push = fit >= 85 and age < 1 h, CV ready |
| D8 | Hot alerts in M1 (no fit score before M2) | M1 Hot alert = age < 2 h and passes the hard filters; the fit condition is added in M2 |
| D9 | Network layout vs the 5 USD budget alarm | Public subnets, no NAT gateway; RDS not publicly accessible, security-group restricted |
| D10 | Commit and PR attribution | Commits authored by Seif only, no trailers. PRs opened from the Claude project keep its "Requested by" line unless Seif asks to drop it |

## 2. Scope per milestone

### M0 Setup
- Repo layout: Maven multi-module, Java current LTS, latest stable Spring Boot, Spring Modulith.
  Modules: `profile`, `ingestion`, `matching`, `tailoring`, `apply`, `notifier`, `tracker`.
  Runnable apps: `api`, `ingestion-worker`, `tailoring-worker`. `web/` and `infra/` directories.
- Flyway migration V1: the 11 tables of the plan's data model, exactly those fields, plus the
  `vector` extension. Every per-user table carries `user_id`.
- Master profile stored as `bullet` rows with IDs, imported from a git-ignored JSON seed file
  (`jobradar.profile.seed-file`); idempotent, IDs stable across re-imports.
- `docker-compose.yml`: Postgres + pgvector, LocalStack (SQS hot queue, backlog queue, DLQ; S3).
- Terraform in `infra/`: VPC, RDS PostgreSQL, S3, ECR, SQS queues + DLQ, Secrets Manager shells, ECS Fargate services (no load balancer, no NAT). `plan` only from tooling; Seif runs `apply`.
- GitHub Actions: build and test on push; deploy with OIDC (no long-lived AWS keys).
- Committed config from the plan: `CLAUDE.md`, `.claude/settings.json`, `.githooks/commit-msg`,
  the two skills, the `compliance-reviewer` agent, README with the 7 guardrails.
- **Verify:** `./mvnw verify` green; `docker compose up -d` healthy and Flyway applies V1;
  `terraform apply` and a deploy run green from a push.

### M1 Ingest
- Careers resolver for the 30 seeded companies: detect ATS (Greenhouse, Lever, Workable,
  SmartRecruiters, Workday), store `ats_type` and `board_token`; custom sites marked `email-alerts`.
- Connectors: Workable search + company widgets, Greenhouse boards, Lever boards, Remotive.
- Normalizer to one `Posting` schema; dedupe; hot and backlog routing; `source_run` rows.
- Telegram alerts by freshness band.
- **Verify:** a first Hot alert arrives in Telegram for a real Egyptian or remote role.

### M2 Tailor
- Hard eligibility filters, pgvector similarity against bullets, LLM rerank of the top few with a
  one-line reason each.
- Tailoring worker: LLM returns bullet IDs and order only; no-invention validator; PDF render;
  page-fit loop; ATS keyword coverage score.
- **Verify:** a Hot posting arrives with a fit score and a CV Seif would send; `/tailoring-eval`
  passes on all golden postings.

### M3 Apply
- Gmail push on one label; parsers for Wuzzuf, Bayt and LinkedIn alert emails.
- Email apply on Approve (sent from the user's Gmail); tracker; follow-up reminders (7–10 days).
- Implemented in the api: the Apply-now alert carries Approve / "I applied" / Skip / Later
  buttons; taps are fetched by long polling (no public endpoint). Only the Approve tap of the
  linked user (private chat id = Telegram user id) calls `ApplyService.approve`, which sends the
  tailored CV from the user's Gmail (`gmail.send`, refresh token in Secrets Manager under
  `app_user.gmail_token_ref`), once per posting, within `daily_apply_cap`. Postings that take no
  email are applied to by the user, who taps "I applied". Follow-up reminders with a drafted
  message come after 8 days. Parsing the user's Gmail label for alert emails is a separate step.
- **Verify:** one real application sent end to end from a Telegram tap, with an `application` row
  and `follow_up_at` set.

### M4 Extras (cut first if time is tight)
- Brave Search for public LinkedIn posts; ATS autofill extension for Workable and Greenhouse;
  weekly digest; React dashboard; invite flow.
- **Verify:** the weekly digest shows replies by source and by CV version.

## 3. Module interfaces

Names are binding; signatures are finalized when each milestone starts.

| Module | Exposes | Consumed by |
| --- | --- | --- |
| `profile` | `ProfileService`: preferences and bullets by `user_id`; one-click delete of all user data | matching, tailoring, apply |
| `ingestion` | `SourceConnector { fetch() -> List<RawPosting> }`, one per source, each with its own schedule and rate limit; `Normalizer`; `Deduper`; publishes `PostingIngested` | matching (via SQS) |
| `matching` | Consumes `PostingIngested`; writes `match` (eligible, fit_score, priority, band, reasons); publishes `MatchScored` | tailoring, notifier |
| `tailoring` | Runs in the tailoring-worker. Consumes `TailorRequested` from `jobradar-tailor-hot` / `jobradar-tailor-backlog` (published by `matching` for eligible Hot matches, and for Warm matches with fit >= 60; never for Backlog); `LlmClient` (bullet IDs only); `TailoringValidator`; `PdfRenderer` (Typst); writes `cv_version` and the PDF under `cv/` in S3; publishes `CvReady` to `jobradar-cv-ready` | notifier, apply |
| `notifier` | `NotificationChannel` (Telegram first, email for digests); emits `UserAction` (Approve, Skip, Later) | apply, tracker |
| `apply` | `ApplyChannel` per channel; runs only on an Approve `UserAction`; enforces `daily_apply_cap`; writes `application` | tracker |
| `tracker` | Application status, follow-up timers, response rates by source and CV version | notifier, api |

**Shared library `llm`:** matching (api) and tailoring (worker) run in different deployables and
both call the model, so `LlmClient`, `EmbeddingClient` and their OpenAI-compatible HTTP
implementation live in an eighth Maven module, `llm`, that both depend on.

**Fit score:** mean cosine similarity of the posting to the user's 5 nearest bullets (pgvector),
mapped linearly from [0.30, 0.70] to [0, 100]. Postings with an embedding fit of 55 or more are
reranked by the LLM, which returns the final fit and a one-line reason. Bullets tagged `verify`
are ignored. Without embeddings there is no fit and the band depends on age alone.

**Queue messages (SQS, JSON):** `PostingIngested { postingId, firstSeenAt, postedAt? }` on the hot
queue when age < 2 h, otherwise on the backlog queue. Failed parses go to the DLQ.

**Rules fixed by the plan:**
- Age = now − min(`posted_at`, `first_seen_at`). A posting with no `posted_at` is fresh only on
  its first sighting.
- Priority = fit × 0.5^(age_hours / 6).
- Bands: Hot (< 2 h, fit >= 80), Warm (2–24 h, batched every 3 h), Backlog (> 24 h, never pushed),
  Deadline (exempt from decay until 3 days before the closing date, then pushed once).
- Dedupe key: apply URL, and a hash of company + title + location (`content_hash`).

**Open for M2 (CV rendering):** the plan's `bullet` table has no place for an entry's heading,
dates and sub-heading, or for the CV header, education, programs and languages. M0 keeps them in
the seed file and links bullets of one entry with an `entry:<slug>` tag. M2 must decide where the
renderer reads them from.

## 4. Testing strategy
- Connector tests use recorded fixtures; no live network calls in tests.
- Band, priority and dedupe rules are unit-tested before the code that uses them.
- Tailoring: fixture profile + fixture postings; assert only existing bullet IDs, no skill, number
  or employer outside the profile, PDF exactly one page.
- Integration tests run against the docker-compose stack (Postgres + pgvector, LocalStack).

## 5. Out of scope
- Any scraping or automation of LinkedIn or Wuzzuf; any extension on their domains.
- Submitting forms on the user's behalf; CAPTCHA solving or bot-detection workarounds.
- Arabic CVs; cover-letter generation beyond the short email note.
- Agent frameworks (LangChain, LangGraph, CrewAI); more than three deployables.
- Billing, public sign-up, mobile app.
