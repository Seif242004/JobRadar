# web

Mobile-first dashboard: onboarding (roles, level, where, job type), matches as cards with the
fit score and freshness, a posting sheet with the tailored CV, its ATS report and fixes, applications,
and Settings (search preferences and the CV summary lines you confirm). Single user.
Layout notes and what was borrowed from other products: [docs/PRODUCT_RESEARCH.md](../docs/PRODUCT_RESEARCH.md).

## Run

1. Start the database and LocalStack (`docker compose up -d`).
2. Start the api with the dashboard profile:
   `SPRING_PROFILES_ACTIVE=dashboard ./mvnw -pl api spring-boot:run`
3. `cd web && npm ci && npm run dev`, then open http://127.0.0.1:5173

## On a phone

Set `JOBRADAR_DASHBOARD_TOKEN` (16+ characters) and `JOBRADAR_PUBLIC_URL` (the https address of your
tunnel) in `.env`. Every dashboard call then needs the token: open `https://<your-url>/?token=...`
once on the phone (or enter the key on the sign-in screen) and it is remembered as a cookie. CV links
in notifications are signed and expire after seven days. The api refuses to start with a public URL and no token.
