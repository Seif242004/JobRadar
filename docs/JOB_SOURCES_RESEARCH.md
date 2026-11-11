# Job sources: what is legal to read, and what is not

Rule for every source: no scraping where the terms forbid it, nothing that costs money, and a personal dashboard only. Checked 2026-11-11. "Verified" means I read the site's own page today. "Unverified" means it came from a search result or general knowledge, so check the terms before building on it.

## 1. The big job sites

| Site | What it exposes | Used by JobRadar |
|---|---|---|
| LinkedIn | No public job-search API for individuals. Its job APIs are for approved partners. Its terms ban automated access. (unverified today, standard knowledge) | Only your own alert emails. Pages are never fetched. |
| Wuzzuf | No public API found. Its terms ban automated access. (unverified today) | Only your own alert emails. Pages are never fetched. |
| Bayt | No public API found. (unverified today) | Only your own alert emails. Pages are never fetched. |
| Indeed | Its old public publisher API is closed to new users. (unverified today) | Alert emails only. |
| Glassdoor, Forasna | No public API found. (unverified today) | Alert emails only. |

## 2. Aggregators that already collect posts (your idea)

I searched for an aggregator with an official feed, API or RSS that covers Egypt and re-publishes social-media posts. I did not find one.

| Source | Verdict | Reason |
|---|---|---|
| Remotive | **Qualifies, already in use** | Free public API (verified). Needs a link back and naming Remotive. Jobs are delayed 24 hours. Must not be passed to other job sites. A personal dashboard is fine. At most 4 calls a day. |
| Himalayas | **Qualifies, not added yet** | Official API (verified): 60 requests a minute and 10,000 a day with a free key, and country and worldwide filters. Allowed on your own site or app. It must not be forwarded to other aggregators. Needs a visible link to the job on Himalayas. Without a key it was rate-limited when I tried. I need your free sign-up at himalayas.app/signup/developer for a key. I have no recorded sample response yet, so the connector waits for the key. |
| We Work Remotely | **Probably qualifies, unverified** | A search result says the RSS feed is free with attribution. Their page returned 403 to my fetch, so I could not read the terms. |
| Jobicy, RemoteOK, Arbeitnow | **Unverified** | Third-party descriptions only. They mention attribution and a request rate limit. I could not reach their sites from this session to read the terms. |
| Jooble | **Does not qualify yet** | Needs an API key request. Its terms bar bots from its site, and I found nothing on Egypt coverage or a free tier. |
| Tanqeeb (Arabic aggregator) | **Does not qualify** | I found no public API or feed. |
| Telegram public channels (t.me/s/...) and Facebook groups | **Do not qualify** | Reading them is scraping. Telegram's official bot API only covers channels a bot is added to, and Facebook groups need authorised access. No aggregator found that republishes them with an official feed. |

## 3. How programs and mentorships are found instead
1. Company career pages and ATS boards (Greenhouse, Lever, Workable), polled every 15 minutes.
2. Program newsletters and talent communities you subscribe to, which arrive in the `JobRadar` Gmail label. The reader understands links to the common ATS sites. See `ALERT_SETUP_CHECKLIST.md`.
3. Alerts from the job boards, with search terms such as "graduate program", "internship program" and "training program".

Not covered: programs announced only in social posts.
