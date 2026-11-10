# JobRadar

Egypt-first job-search copilot. It aggregates Egypt and remote-from-Egypt postings from official
and public sources, scores them against one master profile, tailors a one-page CV from existing
bullets only, and sends a notification saying what to do and when. Nothing is submitted without
an explicit approval.

- Specification: [SPEC.md](SPEC.md)
- Research and architecture: [docs/PLAN.md](docs/PLAN.md)

## Guardrails

These rules are enforced in code and reviewed on every change to ingestion or apply.

1. **Never touch LinkedIn or Wuzzuf with automation.** No scraping, no extension on their
   domains, no Easy Apply clicking.
2. **Read only official or public feeds,** respect each source's terms and rate limits (for
   example Remotive's 4 calls a day and link-back rule), and honour `robots.txt` for anything else.
3. **Nothing is submitted without the user's tap.** Email sends only after Approve; forms are
   always submitted by the user.
4. **No invented content.** The tailoring validator blocks any skill, number or employer that is
   not in the user's bullets; tests cover it.
5. **No CAPTCHA solving or bot-detection workarounds,** ever.
6. **User data stays the user's:** the narrowest Gmail scopes that work (read plus send), and the
   app only processes one label; tokens in Secrets Manager; one-click delete of everything.
7. **Self rate limit:** a daily cap on applications so quality stays high.

## Layout

| Path | Contents |
| --- | --- |
| `profile/`, `ingestion/`, `matching/`, `tailoring/`, `apply/`, `notifier/`, `tracker/` | Spring Modulith modules, one Maven module each (`com.jobradar.<module>`) |
| `llm/` | Shared library: provider-neutral `LlmClient` and `EmbeddingClient` over an OpenAI-compatible HTTP API, with model fallback and a daily call cap |
| `api/` | Deployable: profile, matching, apply, notifier, tracker; owns the Flyway migrations |
| `ingestion-worker/` | Deployable: ingestion |
| `tailoring-worker/` | Deployable: tailoring |
| `infra/` | Terraform: VPC, RDS PostgreSQL, S3, ECR, GitHub OIDC deploy role, budget alarm |
| `web/` | React dashboard (M4) |
| `localstack/` | Init script that creates the local SQS queues and S3 bucket |

## Requirements

JDK 25, Docker. Maven comes from the wrapper.

## Commands

```sh
./mvnw verify                 # build + all tests (needs Docker for the Postgres test container)
./mvnw -pl <module> test      # one module
docker compose up -d          # Postgres + pgvector on 5432, LocalStack (SQS, S3) on 4566
java -jar api/target/api.jar  # run the api; Flyway applies the schema on startup
```

Health endpoints: api `:8080/actuator/health`, ingestion-worker `:8081`, tailoring-worker `:8082`.

Local queues: `jobradar-hot`, `jobradar-backlog`, `jobradar-dlq`. Local bucket: `jobradar-local`.

## Master profile

The profile is a JSON seed file of CV bullets grouped by entry; see `seed/profile.example.json`.
Real seed files hold personal data and are git-ignored (`seed/*`).

```sh
JOBRADAR_PROFILE_SEED_FILE=seed/me.profile.json java -jar api/target/api.jar
```

The import runs on api startup and is safe to repeat: existing bullets keep their IDs, new ones
are added, nothing is deleted. Each bullet is tagged with its entry's technologies, an
`entry:<slug>` tag, and `verify` when its wording is not confirmed yet.

## Infrastructure

```sh
cp infra/terraform.tfvars.example infra/terraform.tfvars   # set budget_alert_email
terraform -chdir=infra init
terraform -chdir=infra plan
terraform -chdir=infra apply
```

After the first apply, add two repository variables in GitHub (Settings > Secrets and variables >
Actions > Variables): `AWS_ROLE_ARN` (the `github_deploy_role_arn` output) and `AWS_REGION`
(`eu-central-1`). From then on every push to `main` builds the three images and pushes them to
ECR through OIDC; no AWS keys are stored in GitHub.

## Git hook

```sh
git config core.hooksPath .githooks
```
