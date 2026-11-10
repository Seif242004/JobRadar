---
name: add-source-connector
description: Add or change a job source connector (ATS feed, RSS, or email-alert parser).
disable-model-invocation: true
---
Add the connector for $ARGUMENTS:
1. Confirm the source is allowed: official/public API, RSS, or the user's Gmail label.
   If it needs scraping, a login, or LinkedIn/Wuzzuf access, stop and tell me.
2. Record 2-3 real responses as fixtures in src/test/resources/fixtures/$ARGUMENTS/.
3. Implement SourceConnector: fetch, map to Posting (postedAt if the source gives it,
   firstSeenAt always), return.
4. Respect the source's rate limit in its schedule (Remotive: at most 4 calls a day).
5. Tests: mapping from fixtures, dedupe against an existing posting, freshness band.
6. Run ./mvnw -pl ingestion verify and show the output.
