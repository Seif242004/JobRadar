# JobRadar
Egypt-first job-search copilot. Spec: SPEC.md. Research and architecture: docs/PLAN.md.

## Stack
Java (current LTS), Spring Boot, Spring Modulith, Maven wrapper, PostgreSQL + pgvector, Flyway,
SQS, S3, React (web/), Terraform (infra/), GitHub Actions.

## Commands
- Build + all tests: ./mvnw verify
- One module: ./mvnw -pl <module> test
- Local stack: docker compose up -d   (Postgres+pgvector, LocalStack for SQS/S3)
- Infra: terraform -chdir=infra plan  (never apply or destroy; Seif runs those)

## Rules
- IMPORTANT: never scrape or automate LinkedIn or Wuzzuf. Sources are official/public APIs, RSS,
  or the user's own Gmail label only.
- Never submit an application without an explicit user approval event.
- Tailoring only selects and reorders existing bullet IDs. Never invent skills, numbers or employers.
- Postings are shared; every per-user table has user_id.
- Freshness first: postings under 2 hours old go to the hot queue.
- Connector tests use recorded fixtures; no live network calls in tests.
- Secrets come from env vars or Secrets Manager; never commit .env.
- Commits: author is Seif only. No Co-Authored-By, no Claude-Session trailer, and no mention
  of Claude in commit messages or PR descriptions.
