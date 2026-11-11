# web

Local, read-only dashboard (summary, matches, applications). Single user, no login, no writes.
Decision recorded in SPEC.md (M4): local only, no load balancer, no invite flow.

## Run

1. Start the database (`docker compose up -d`), or tunnel to RDS yourself. RDS is not publicly
   accessible and there is no bastion, so a tunnel is your own setup.
2. Start the api bound to 127.0.0.1 with the dashboard profile:
   `./mvnw -pl api spring-boot:run -Dspring-boot.run.profiles=local-dashboard`
3. `cd web && npm ci && npm run dev`, then open http://127.0.0.1:5173

The `/api/dashboard/*` endpoints exist only under the `local-dashboard` profile; the deployed api
does not expose them.

On Windows, `start-jobradar.bat` runs the api with the `dashboard` profile: the same endpoints as `local-dashboard`,
but the matching and notifier listeners stay on, and the api is bound to 127.0.0.1 through `SERVER_ADDRESS`.
