# Product research: Egyptian and regional career products

Scope: what JobRadar should borrow for UX, mobile layout, language and onboarding. Method: web
search results only. The research session could not open the products' own sites (the network
allowlist blocked them), so everything below is from search snippets and is marked where it is
thin. Nothing here is scraped, and no product was logged into.

## What was found

| Product | What it is (from search results) | Worth borrowing |
| --- | --- | --- |
| **Wuzzuf** (BasharSoft) | Egypt's largest job board; new app for Egypt and MEA with an AI job-search assistant, a "Help Me Write" tool, CV parsing and smart matching, free to use | Matching explained in plain words, AI help inside the flow, built for Egyptian titles and cities |
| **Forasna** (BasharSoft) | Sister board, same company | Nothing separate found |
| **Qualiphi** | Cairo university career-services platform (500k+ students, 40 universities); bought Career Club from iCareer | Student framing: internships and graduate roles first |
| **Matloub** | AI platform that trains youth and connects them to jobs; AI CV builder and matching | Guided CV building and one clear next step |
| **GenZawy** | Student-built Gen Z career guidance app (Al-Ahram Canadian University) | Short guided onboarding for people with no experience |
| **TalentsNexus, MisuJob** | AI recruitment and job-search platforms listing Cairo roles | Little detail found |
| **LyRise** | Matches North African engineers with remote and international AI roles | Remote-first listing for engineers |
| **"Talk with Raya"** | **Not found.** Searches return Raya Holding (a listed Egyptian group) and its careers site, not a consumer app. Needs a link from Seif | Unknown |

Not found at all: Jobzella, Tanqeeb, Jobiano, Hirelogics (no usable results).

## What this means for JobRadar

1. **Ask first, show later.** The old dashboard listed matches before anyone said what they wanted.
   Now the first screen is a short onboarding: roles, level, where, job types. Every answer stays
   editable in Settings, and changing it re-filters at once.
2. **Level is a hard filter.** A student should not see "Senior" or "5+ years". The filter says why
   each posting was shown or hidden ("Asks for 5+ years of experience").
3. **One card, one decision.** Mobile-first cards: title, company, where, fit, freshness, and three
   actions (open CV, apply, skip). No tables on a phone.
4. **The CV must open on a phone.** CV links are signed and served through the dashboard URL.
5. **Explain the score.** Fit and ATS get short reasons and concrete fixes, as Wuzzuf's AI tools do,
   but only from what the user's own CV already says.
6. **Arabic and English.** Postings come in both; the layout supports right-to-left text inside
   cards (`dir="auto"`). A full Arabic interface is not built yet.
7. **Notifications that match intent.** One daily digest of the best new matches, and an instant
   push only for strong, fresh ones.

## Not copied

Anything that needs scraping or automation of Wuzzuf, LinkedIn or Bayt (see the guardrails in
README.md), and any paid feature.

## Sources

- Wuzzuf app announcement (search result): https://wuzzuf.net/careers/introducing-the-new-wuzzuf-app/
- BasharSoft acquires iCareer: https://www.zawya.com/en/press-release/companies-news/basharsoft-acquires-icareer-to-create-egypts-largest-integrated-employment-and-career-services-group-bl6ltkfs
- Qualiphi: https://www.wearetech.africa/en/fils-uk/solutions/egyptian-edtech-firm-qualiphi-links-universities-to-job-market-needs
- Matloub: https://vc4a.com/ventures/matloub/
- GenZawy: https://www.egypttoday.com/Article/1/146978/GenZawy-A-student-built-platform-helping-Gen-Z-navigate-the
- LyRise: https://allafrica.com/stories/202506170577.html
