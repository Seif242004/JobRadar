# JobRadar: research and architecture notes

Written November 2026. These are the planning notes the project started from. Where the implementation differs
(push notifications go through ntfy instead of Telegram, and the AWS deployment is defined in Terraform but optional),
the README and SPEC.md are current.

## Summary

**Verdict:** worth building as a personal job-search tool and as an engineering project. Not as a startup yet: the global market is crowded, and the Egyptian players are single-platform.

**The gap it fills:** global auto-apply tools (Jobright, Simplify, LoopCV, LazyApply) are built around US boards and LinkedIn Easy Apply. Egyptian platforms (Wuzzuf, Bayt) only match jobs inside their own site. Nobody aggregates **Egypt + remote-from-Egypt** sources, scores them, tailors a 1-page CV, and tells you **what to do and when**.

**Five decisions this research led to:**

1. **No LinkedIn bots or scraping extensions.** LinkedIn bans them explicitly, and auto-apply users report account restrictions. Your LinkedIn is worth more than any tool.
2. **No manual capture as the core.** LinkedIn posts are covered automatically, as far as compliance allows, through LinkedIn's own emails to your inbox and a search API over public posts, with zero clicks from you.
3. **"One-tap apply", not blind auto-apply.** Egypt's real channels are email-your-CV, Google Forms, Wuzzuf, and company ATS forms. The system prepares everything; you approve. Email applications are sent for you on approval.
4. **Notifications drive everything:** apply now, deadline soon, follow up, weekly review.
5. **Spring Boot on AWS**, as a modular monolith with three deployables.

**Decided since:** working name JobRadar; multi-user from day one so friends can join later; English CV only; and **freshness first**, because most CVs are never read once a posting is past its first 24 hours. The newest postings always jump the queue, while older ones stay visible but stop interrupting you.

## Market research: who does this today

The market splits into three kinds of tool: **auto-apply engines** that submit for you, **form-fillers** that speed up applications you start, and **copilots** that find, score and tailor. Every one is US- or Europe-first.

| Tool | How it finds jobs | How it applies | Weak spot |
| --- | --- | --- | --- |
| **LazyApply** | The board you open in your browser (LinkedIn, Indeed, ZipRecruiter, Glassdoor) | Chrome extension clicks through LinkedIn Easy Apply and similar forms in your own session, up to 1,500 a day | Trustpilot in the low 2s; generic answers; on a widely cited list of blacklisted LinkedIn plugins |
| **LoopCV** | Aggregates boards and collects new jobs as often as hourly | Background auto-apply plus recruiter emails; LinkedIn Easy Apply through an extension | Volume over quality; LinkedIn risk |
| **JobCopilot** | Matched jobs across career pages and boards, with a European focus | Fills and submits applications with AI-generated resumes and cover letters | Same CV everywhere unless tailored; you find fewer good matches |
| **Jobright** | Own database of about 8 million jobs, fit score per job | Agent autofills Workday, Greenhouse, Lever, Taleo and iCIMS forms, up to hundreds a day | Reviewers flag ATS volume detection and AI hallucination in resumes |
| **Simplify** | Job matches inside its own board | Free extension autofills forms on 100+ portals (Workday, Greenhouse, Lever, iCIMS, Taleo) when you open them; you submit | Not automatic; US-centric job matches |
| **AIApply** | Built-in job board | Auto-apply across LinkedIn Easy Apply, Indeed and career portals, on credit packs | Credit limits; LinkedIn risk |

**Two patterns explain all of them:**

- **Sourcing** = their own aggregated database (built from public ATS feeds and job boards) or whatever board you open in your browser.
- **Applying** = either a browser extension acting inside your logged-in session, or a cloud agent filling ATS forms. There is no official API that lets a third party submit applications to LinkedIn, so every LinkedIn auto-apply tool has to act as you, inside your session.

## What the market teaches

The tools that apply blindly at volume have the worst reputations; the ones that keep a human in the loop have the best.

- **LinkedIn automation costs accounts.** LinkedIn's help centre bans third-party software, including browser plug-ins and extensions, that scrape, modify the appearance of, or automate activity on its website. One 2026 report found 23% of users relying on browser extensions had their LinkedIn accounts restricted within 90 days.
- **Volume hurts results.** 200 generic applications often produce fewer interviews than 30 tailored ones, and reviewers say Workday and Greenhouse setups detect volume anomalies and deprioritize those candidates.
- **AI tailoring hallucinates.** Reviews of the volume tools flag invented content in AI-generated resumes. Tailoring has to select from what you actually did, never write new claims.
- **Real failure points are mechanical:** brittle ATS forms, CAPTCHAs and anti-bot defenses, and poor targeting.
- **What users love:** Simplify's free autofill (you stay in control, it removes the typing) and Jobright's fit scoring. Both are human-in-the-loop.

## The Egypt landscape

Egypt's job market runs on one dominant board, LinkedIn posts, and direct channels like email and Google Forms. The local AI tools either serve employers or automate LinkedIn.

**Where Egyptian jobs live**

| Source | What it is | Can we read it automatically? |
| --- | --- | --- |
| **Wuzzuf** | The dominant board: 55,000+ companies served, 2.5M+ vacancies published, 100K+ monthly active job seekers. Its new app adds AI job matching and real-time alerts | Not by scraping: parent company Basharsoft's ATS terms ban automated scraping without written consent. **Yes through its alert emails** to your inbox |
| **Forasna, iCareer** | Also Basharsoft; Forasna leans entry-level and blue-collar | Same as Wuzzuf |
| **Bayt** | MENA-wide, AI-matched jobs with smart alerts; covers Egypt | Through alert emails |
| **Tanqeeb** | Aggregates listings from 100+ Arab job boards | Use as a cross-check, not a source |
| **LinkedIn Jobs + posts** | Where multinationals and startups post; many roles appear only as posts | Job alert emails; posts covered in the next section |
| **Workable** (used by many startups) | Public, keyless endpoints: per-company widget feed and a cross-employer keyword search | **Yes**, official public JSON |
| **Greenhouse, Lever, Ashby** | Used by international companies with Cairo offices | **Yes**: Greenhouse's Job Board API needs no auth for reading jobs |
| **Remote boards** (Remotive, Himalayas, We Work Remotely) | Remote roles; Remotive marks location restrictions such as "Worldwide" | **Yes**, public APIs and RSS, each with its own terms |

**Local AI competitors**

- **JobDoceo** (Egyptian startup): resume improvement, ATS analysis and automated applications. Its Chrome extension automates applications on LinkedIn, so it carries the same account risk as LazyApply.
- **Wuzzuf and Bayt apps:** AI matching, but only for their own listings.
- **TalentsNexus, TalentAI Egypt:** employer-side hiring platforms; TalentAI lets candidates apply inside WhatsApp with an AI interview.

**How Egyptians actually apply:** email your CV to HR, Google Forms (very common in LinkedIn posts), in-platform apply on Wuzzuf, LinkedIn Easy Apply, and company ATS forms (Workable, Workday). Any useful tool has to handle all five, not just ATS forms.

## The LinkedIn posts problem

Many Egyptian roles appear only as LinkedIn posts, and there is no compliant way to read your feed automatically. So the system covers posts from outside LinkedIn, with zero clicks from you.

| Option | How it works | Verdict |
| --- | --- | --- |
| Feed-scanning extension | Tools like FeedJobs auto-scroll your LinkedIn feed and detect hiring posts | **Rejected.** This is exactly the extension behaviour LinkedIn bans; your account carries the risk |
| Cloud scraper | A server logs in or crawls LinkedIn | **Rejected.** Same ban, plus legal exposure |
| Manual share to a bot | You forward posts you see | **Demoted** to an optional fallback, because it adds hassle |
| **LinkedIn's own emails** | Job alert emails, plus notification emails for people and companies you follow, read from **your own inbox** | **Accepted.** Your data, no LinkedIn access by the system. Test in week one which post notifications LinkedIn actually emails |
| **Search API over public posts** | Queries like `site:linkedin.com/posts hiring backend intern Cairo` on a search API, newest first; follow only the outbound apply link (Google Form, careers page) | **Accepted.** The system never touches LinkedIn. Google's Custom Search JSON API closed to new customers in January 2026 and shuts down January 1, 2027, so use **Brave Search API** (its own index, structured JSON) |

**Honest limit:** search indexing lags hours behind posting, so posts are not first-hour like the ATS feeds. The system compensates by polling Workable and Greenhouse company boards every 15–30 minutes, which is where many of the same roles land officially.

## The product

One master profile in, a short list of ready-to-send applications out, with a notification telling you exactly when to act.

**Sources and how fresh they are**

| Source | Method | Freshness |
| --- | --- | --- |
| Workable search + company widgets | Public JSON, filtered to Egypt and remote | Poll every 15–30 min |
| Greenhouse / Lever / Ashby watchlist | Public board APIs for a list of companies you pick | Poll every 15–30 min |
| Wuzzuf, Bayt, LinkedIn job alerts | Gmail API push on a label, then parse the alert email | Minutes after the alert arrives |
| LinkedIn notification emails | Same inbox pipeline | Minutes after the email |
| Public LinkedIn posts | Brave Search API, newest first | Hourly; hours behind posting |
| Remote boards | Remotive API (at most 4 calls a day, jobs delayed 24 h), Himalayas, WWR RSS | 2–4 times a day |

**Apply channels: one tap, never blind**

| Channel | What the system does | What you do |
| --- | --- | --- |
| **Email your CV to HR** | Drafts the email with the tailored PDF and a short note | Tap **Approve**; it sends from your Gmail |
| **Google Form** | Prepares answers from your profile; prefilled link where the form allows it | Review, submit |
| **Workable / Greenhouse / Lever form** | Tailored PDF ready; later, a companion extension autofills these ATS sites only | Review, submit |
| **Wuzzuf, LinkedIn Easy Apply** | Tailored PDF and answers ready, deep link to the job | Apply yourself; nothing is automated on these sites |

**Notifications (Telegram bot first, email for digests)**

- **Apply now:** fit 85%+, posted under an hour ago, CV ready. One tap to open.
- **Deadline soon:** bootcamps, summer programs and forms with closing dates.
- **Follow up:** 7–10 days after applying with no reply, with a drafted message.
- **Interview booked:** prep pack (company notes, the CV you sent, likely questions).
- **Weekly review:** applied, replies, response rate by source and by CV version.

**What makes it yours, not a clone**

- Egypt-first eligibility: location, "remote from Egypt" checks, graduation year, internship vs working-student vs full-time.
- Arabic and English postings parsed and matched; your CV stays English.
- A 1-page CV built only from your own bullets, with an ATS keyword score per job.
- Tracker that learns which sources and CV versions get replies.

## Freshness first

A posting's value drops fast after it goes live, so every decision in the system is ranked by age as well as fit.

**Age** = now minus the earlier of `posted_at` (when the source gives it) and `first_seen_at` (when JobRadar first saw it).

**Priority** = fit score × freshness, where freshness halves every 6 hours. A strong match from this morning outranks a slightly better match from yesterday.

| Band | Age | What happens |
| --- | --- | --- |
| **Hot** | Under 2 hours, fit 80+ | Goes to the **hot queue**; CV tailored immediately; instant **Apply now** push |
| **Warm** | 2–24 hours | Batched into an alert every 3 hours, best first |
| **Backlog** | Over 24 hours | Never pushed; stays in the dashboard, sorted by priority, and in the weekly digest |
| **Deadline** | Has a closing date | Exempt from decay until 3 days before the deadline, then pushed once |

**How the pipeline enforces it**

- Ingestion routes each posting by age: hot postings to a dedicated **hot SQS queue** with its own tailoring consumers, everything else to a **backlog queue** with one low-priority consumer. A backlog flood can never delay a fresh posting.
- ATS watchlist sources poll every 10–15 minutes, and Gmail alerts arrive by push, so a fresh posting reaches you within minutes of first being seen.
- Postings with no `posted_at` are treated as fresh only on their first sighting; on later runs their age counts from `first_seen_at`.

## Company watchlist (first 30)

These 30 employers with engineering teams in Egypt seed the watchlist; friends can add their own later. Hiring and careers sites change, so a **careers resolver** checks each one at setup: it detects the ATS from the careers page (Greenhouse, Lever, Workable, SmartRecruiters, Workday) and stores the board token, or falls back to that company's job-alert emails when it runs a custom site.

| # | Company | Type | Why it fits you |
| --- | --- | --- | --- |
| 1 | Valeo Egypt | Multinational R&D | Large automotive software centre in Smart Village; hires interns |
| 2 | Siemens (EDA and Digital Industries Software) | Multinational R&D | Cairo R&D teams; C++ and backend tooling |
| 3 | Microsoft Egypt | Multinational R&D | Cairo engineering and AI roles |
| 4 | IBM Egypt | Multinational R&D | Enterprise software and AI |
| 5 | Dell Technologies Egypt | Multinational R&D | Enterprise and cloud products; hires interns |
| 6 | Synopsys Egypt | Multinational R&D | EDA software, strong C++ and tooling |
| 7 | \_VOIS Egypt (Vodafone) | Multinational tech hub | Large Java and cloud engineering hub |
| 8 | Instabug | Product startup | B2B SaaS built in Cairo for global customers |
| 9 | Paymob | Fintech | Payments backend |
| 10 | MNT-Halan | Fintech | Egypt's unicorn; lending and payments platforms |
| 11 | Breadfast | Commerce | Top-ranked Egyptian startup; logistics-heavy backend |
| 12 | Thndr | Fintech | Retail investing app |
| 13 | Khazna | Fintech | Consumer finance |
| 14 | Blnk | Fintech | Payments and consumer credit |
| 15 | Telda | Fintech | Consumer banking app |
| 16 | MoneyFellows | Fintech | Social savings and lending |
| 17 | Bosta | Logistics | Shipping platform, event-driven backend |
| 18 | Nawy | Proptech | Real-estate marketplace |
| 19 | Vezeeta | Healthtech | Booking platform at regional scale |
| 20 | Sylndr | Marketplace | Used-car platform |
| 21 | elmenus | Consumer | Food discovery and ordering |
| 22 | Rology | AI healthtech | AI radiology across 13 countries |
| 23 | Fawry | Fintech (established) | Egypt's largest payments network; big Java estate |
| 24 | Sumerge | Software house | Banking and enterprise Java |
| 25 | ITWorx | Software house | Enterprise and government software |
| 26 | Giza Systems | Systems integrator | Large engineering employer |
| 27 | Link Development | Software house | Web and enterprise projects |
| 28 | Raya Information Technology | IT services | Enterprise IT and software |
| 29 | Brightskies | Engineering services | Cloud, automotive and data engineering |
| 30 | Robusta | Product studio | Web and mobile product engineering |

## Architecture

&#91;embedded content: system architecture · sources to approved application\]

Every posting moves one way through a queue, so a slow LLM call never blocks ingestion, and nothing leaves the system until you approve it.

**Three deployables, not eight microservices.** Start as a modular Spring Boot app (Spring Modulith keeps module boundaries enforced) split into three processes that scale differently. You still get the microservices story for interviews, without eight services to run on a student budget.

| Deployable | Modules inside | Why separate |
| --- | --- | --- |
| **api** | profile, matching, apply, notifier, tracker, web API for the React app | Always on, light |
| **ingestion-worker** | source connectors, Gmail push handler, normalizer, dedupe | Scheduled, bursty, talks to the outside world |
| **tailoring-worker** | LLM bullet selection, PDF rendering (Typst or LaTeX), page-fit loop, ATS score | Slow and CPU-heavy; consumes from the queue |

**AWS mapping**

- **Compute:** ECS Fargate, one service per deployable; EventBridge Scheduler triggers connector runs.
- **Data:** RDS PostgreSQL with pgvector for embeddings; S3 for raw postings and generated PDFs.
- **Messaging:** SQS between ingestion, matching and tailoring, with a dead-letter queue for failed parses.
- **Secrets:** Secrets Manager for LLM keys and your Gmail and Telegram tokens.
- **Ops:** CloudWatch logs and alarms; Terraform for everything; GitHub Actions with OIDC for deploys.
- **Cost guard:** $5 budget alarm; one small RDS instance; scale the workers to zero between runs.

**LLM use, kept cheap:** filters and embeddings cut the list first, so the LLM only reranks and tailors the top few postings a day. Keep the provider behind an interface so you can swap models.

## Data model and key flows

The master profile is stored as individual bullets with IDs, so tailoring can only pick from what you wrote and every CV is traceable.

**Core tables (PostgreSQL)**

Postings are shared by everyone; everything personal carries a `user_id`, so friends can join without a redesign.

| Table | Scope | Key fields |
| --- | --- | --- |
| `app_user` | per user | id, email, telegram\_chat\_id, gmail\_token\_ref, daily\_apply\_cap, created\_at |
| `profile` | per user | user\_id, preferences: roles, cities, remote, job types, graduation date |
| `bullet` | per user | id, user\_id, section (experience, project, skill), text, tags, embedding |
| `company` | shared | id, name, careers\_url, ats\_type, board\_token, last\_resolved\_at |
| `watchlist` | per user | user\_id, company\_id |
| `posting` | shared | id, company\_id, source, source\_id, title, location, remote\_policy, language, description, apply\_channel, apply\_target, deadline, posted\_at, first\_seen\_at, content\_hash, embedding |
| `match` | per user | user\_id, posting\_id, eligible, fit\_score, priority, band, reasons |
| `cv_version` | per user | id, user\_id, posting\_id, bullet\_ids\[\], pdf\_s3\_key, ats\_score |
| `application` | per user | id, user\_id, posting\_id, cv\_version\_id, channel, status, sent\_at, follow\_up\_at |
| `notification` | per user | id, user\_id, type, posting\_id, sent\_at, acted\_at, action |
| `source_run` | shared | source, started\_at, items\_found, new\_items, errors |

**Flow 1, ingest:** scheduler fires a connector → fetch → normalize to one schema → dedupe by apply URL and a hash of company, title and location → store → publish the new posting to SQS.

**Flow 2, match:** hard filters first (Egypt or remote-from-Egypt, level, job type, deadline not passed) → embedding similarity against your bullets → LLM rerank of the top few, with a one-line reason per score.

**Flow 3, tailor:** the LLM returns only bullet IDs and an order → a validator rejects any skill or claim not in your profile → render the PDF → if it spills past one page, drop the lowest-ranked bullet and re-render → compute keyword coverage against the posting.

**Flow 4, notify and apply:** score 85+ and posted under an hour ago triggers **Apply now** with Approve, Skip and Later buttons → Approve calls the apply service for that channel → an `application` row is created and a follow-up timer is set.

## Guardrails

These rules are what make the project safe to use and a good interview story; write them into the README and enforce them in code.

1. **Never touch LinkedIn or Wuzzuf with automation.** No scraping, no extension on their domains, no Easy Apply clicking.
2. **Read only official or public feeds,** respect each source's terms and rate limits (for example Remotive's 4 calls a day and link-back rule), and honour `robots.txt` for anything else.
3. **Nothing is submitted without your tap.** Email sends only after Approve; forms are always submitted by you.
4. **No invented content.** The tailoring validator blocks any skill, number or employer that is not in your bullets; tests cover it.
5. **No CAPTCHA solving or bot-detection workarounds,** ever.
6. **Your data stays yours:** the narrowest Gmail scopes that work (read plus send), and the app only processes one label; tokens in Secrets Manager; one-click delete of everything.
7. **Rate-limit yourself:** a daily cap on applications so quality stays high.

## Roadmap

&#91;embedded content: roadmap · 5 milestones, no fixed dates\]

Each milestone ships something usable, and M1 alone already helps your own search.

| Milestone | Build | Done when |
| --- | --- | --- |
| **M0 Setup** | Master profile as bullets with IDs; multi-user schema; Spring Boot skeleton with the modules; Terraform for VPC, RDS, S3, ECR; GitHub Actions with OIDC | `terraform apply` and a deploy run green from a push |
| **M1 Ingest** | Careers resolver for the 30 companies; Workable search, Greenhouse/Lever boards, Remotive; normalizer, dedupe, hot and backlog queues; Telegram alerts by freshness band | Your first Hot alert arrives for a real Egyptian or remote role |
| **M2 Tailor** | Eligibility filters, pgvector matching, LLM rerank; tailoring worker with the no-invention validator and a 1-page PDF | A Hot posting arrives with a fit score and a CV you would actually send |
| **M3 Apply** | Gmail push for Wuzzuf, Bayt and LinkedIn alert emails; email apply on Approve; tracker and follow-up reminders | One real application sent end to end from a Telegram tap |
| **M4 Extras** | Brave Search for public LinkedIn posts; ATS autofill extension for Workable and Greenhouse; weekly digest; React dashboard; invite flow for friends | The weekly digest shows which sources and CV versions get replies |

If time gets tight, cut M4 first. M0 to M3 is a complete, deployable project.

## Sources

Checked November 10, 2026.

- **Auto-apply tools:** [Techloy on extension account restrictions](https://www.techloy.com/jobcopilot-alternatives-ai-job-search-engines-edit-before-submit/) · [Resumly LazyApply review](https://resumly.ai/answers/lazyapply-review) · [TryApplyNow LazyApply review](https://www.tryapplynow.com/blog/lazyapply-review) · [Scoutify on LinkedIn auto-apply](https://scoutify.com/blog/linkedin-auto-apply/) · [LoopCV vs AIApply](https://loopcv.pro/aiapply-alternative) · [LoopCV JobCopilot review](https://www.loopcv.pro/directory/jobcopilot/) · [Jobright review (ATS Verification)](https://atsverification.com/blog/jobright-ai-review-2026/) · [Simplify Copilot](https://simplify.jobs/copilot) · [Careery on auto-apply failure points](https://careery.pro/blog/best-ai-auto-apply-tools-2026)
- **LinkedIn rules:** [LinkedIn: prohibited software and extensions](https://www.linkedin.com/help/linkedin/answer/a1341387) · [FeedJobs extension](https://chromewebstore.google.com/detail/afdaeankhmmpkdljifpgpifcddcegijo)
- **Egypt:** [Wuzzuf about](https://wuzzuf.net/about-us) · [New Wuzzuf app](https://wuzzuf.net/careers/introducing-the-new-wuzzuf-app/) · [Recruitera terms (Basharsoft)](https://recruitera.ai/terms-conditions) · [Bayt app](https://play.google.com/store/apps/details?id=com.bayt&hl=en_US) · [Qureos: Egypt recruitment platforms](https://www.qureos.com/hiring-guide/top-recruitment-platforms-in-egypt) · [JobDoceo Autopilot](https://chromewebstore.google.com/detail/jobdoceo-autopilot/mchnkpbkejjfkeohkajanjhcejolomgn) · [Egypt AI startups list](https://www.startuplist.africa/countries/egypt/industries/artificial-intelligence) · [TalentAI Egypt](https://talentai-eg.com/)
- **Data sources:** [Workable public endpoints](https://dev.to/kunnu_data/workable-has-a-public-search-api-across-every-employer-heres-how-to-use-it-47k6) · [Greenhouse Job Board API](https://jobspipe.dev/guides/greenhouse-jobs-api) · [Remotive API](https://github.com/remotive-com/remote-jobs-api) · [Google Custom Search API shutdown](https://www.firecrawl.dev/blog/google-search-json-api-alternative) · [Brave Search API](https://brave.com/learn/google-api-shutdown/)

* **Egypt employers:** [Seedtable via Mean CEO (Nov 2026)](https://blog.mean.ceo/startups-egypt-news-november-2026/) · [Startupik top Egyptian startups](https://startupik.com/top-startups-in-egypt/?amp=1) · [Nucamp: companies hiring in Egypt](https://www.nucamp.co/blog/top-10-tech-startups-hiring-junior-developers-in-egypt-in-2026) · [ITIDA startup ecosystem report 2026](https://itida.gov.eg/English/MediaCenter/Announcements/Documents/egypt-startup-ecosystem-report-2026.pdf) · [Clutch: software developers in Egypt](https://clutch.co/eg/developers)
