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
| `web/` | Local read-only React dashboard (M4) |
| `scripts/` | `gmail_authorize.py`: one-time Gmail consent (scope `gmail.send` only) that prints the refresh-token secret to store |
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

Health endpoints: api `:8080/actuator/health`, ingestion-worker `:8081`, tailoring-worker `:8082` (18080 to 18082 in the local run below).

Local queues: `jobradar-hot`, `jobradar-backlog`, `jobradar-dlq`. Local bucket: `jobradar-local`.

## Daily use (Windows)

**Start:** double-click `start-jobradar.bat` (or run it from a terminal). It starts Docker, the database, the
api, the two workers and the dashboard, then opens the dashboard in Edge at http://127.0.0.1:5173. The first run
builds the project and can take several minutes. **Stop:** `stop-jobradar.bat` (add `all` to stop the database too;
your data is kept). **Start at sign-in:** `powershell -File scripts\windows\autostart.ps1` (add `-Off` to undo).
Keep the PC on: the sources poll every 15 minutes and Gmail alerts every 5.

**Notifications (free, through ntfy):**
1. Install the **ntfy** app on your phone (or open https://ntfy.sh in a browser).
2. Subscribe to the topic stored as `JOBRADAR_NTFY_TOPIC` in `.env`. The topic name is the only secret, so do not
   share it. (`<topic>-taps` is used by the buttons; do not subscribe to it.)
3. You get two kinds of message:
   - **Apply now**: a fresh, well-fitting posting with a tailored one-page CV ready. Buttons:
     **Approve & send** (sends the application from your Gmail, only after this tap; needs the Gmail send
     permission), **Skip** (records that you passed on it), **Later** (records "later"; there is no reminder yet). Postings that apply on a
     website show **I applied** instead and open the posting; you apply on the site yourself.
   - **New matches** digests of the day's other good postings, with links.
   Nothing is ever submitted without your tap.

**Where to see things:** the dashboard shows the summary, your best matches with their fit score and reasons, and
your applications. Tailored CVs are stored as PDFs in the local bucket `jobradar-local` (under `cv/<user id>/<posting id>/`);
the Apply now message links to the one for that posting.

**Alerts from Wuzzuf, Bayt and LinkedIn:** turn on job alerts on each site with your Gmail address. In Gmail, a filter
must apply the label `JobRadar` to those senders (it only adds the label; nothing is archived, hidden or forwarded).
To add Bayt, edit that filter (Settings > Filters and blocked addresses > edit) and add the Bayt sender address to
its From line, for example `jobalerts-noreply@linkedin.com OR wuzzuf.net OR bayt.com`. The reader looks only at
messages carrying that label.

**If something looks wrong:** read the files in `logs\` (one per app). If the dashboard is empty, wait a few minutes
after starting: the first source poll and the matching need time.

## Run locally (no AWS, no cost)

Everything runs on one machine: Postgres with pgvector and LocalStack (SQS, S3, Secrets Manager)
in Docker, and the three Spring Boot apps from Maven. Nothing here needs an AWS account.

```sh
cp .env.example .env                      # then fill in the keys listed below (git-ignored)
docker compose up -d postgres localstack
./mvnw -DskipTests install
```

Run each app from the repo root with the `.env` values exported (for example `set -a; . ./.env; set +a`).
The ports below avoid 8080, which is often taken on a dev machine:

```sh
PORT=18080 ./mvnw -q -pl api spring-boot:run               # api, runs the Flyway migrations
PORT=18081 ./mvnw -q -pl ingestion-worker spring-boot:run  # sources, Gmail alerts, resolver
PORT=18082 ./mvnw -q -pl tailoring-worker spring-boot:run  # CV tailoring (needs Typst)
```

If the LocalStack init script did not run (it needs LF line endings, see `.gitattributes`), create
the queues and bucket by hand: `docker compose exec -T localstack sh < localstack/init-aws.sh`.

Environment keys (values stay in `.env`, never committed):

| Key | What it is |
| --- | --- |
| `JOBRADAR_AI_KEY` | NVIDIA Build API key (free tier) for chat and embeddings |
| `JOBRADAR_AI_MODEL` | chat model; default `nvidia/nemotron-3.5-lightning-30b-a3b` |
| `JOBRADAR_MATCHING_FIT_SIMILARITY_FLOOR` / `_CEILING` | maps embedding similarity to fit 0..100; `0.10` / `0.35` suits the default embedding model |
| `JOBRADAR_NTFY_TOPIC`, `JOBRADAR_NTFY_CHAT_ID` | free push channel through ntfy.sh; the topic is the secret, use a long random one |
| `JOBRADAR_GOOGLE_CLIENT_ID`, `JOBRADAR_GOOGLE_CLIENT_SECRET` | OAuth "Desktop app" client of your own Google Cloud project (free, Gmail API enabled, you as test user) |
| `JOBRADAR_GMAIL_ALERT_LABEL` | the one Gmail label the alert reader may read (default `JobRadar`) |
| `JOBRADAR_TYPST_BINARY` | path of the Typst executable (`winget install Typst.Typst`) |
| `JOBRADAR_PROFILE_SEED_FILE` | absolute path of your master profile JSON; the api imports it on startup |
| `JOBRADAR_DB_*`, `JOBRADAR_AWS_ENDPOINT`, `JOBRADAR_SQS_*`, `AWS_*` | the local Postgres and LocalStack defaults from `.env.example` |

Gmail alerts: create a Gmail label `JobRadar` and a filter that only applies it to the alert senders.
Run `python scripts/gmail_authorize.py --read-only` (with `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`
set), store the printed JSON in LocalStack
(`awslocal secretsmanager create-secret --name jobradar/gmail/<user id> --secret-string ...`) and set
`app_user.gmail_token_ref` to that name.

A new profile is not matched against postings that already exist. To match them, send each posting id
to the backlog queue as `{"postingId": <id>}`.

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
