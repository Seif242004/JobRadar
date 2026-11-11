# JobRadar autofill (Greenhouse and Workable only)

A small Manifest V3 extension that fills the plain contact fields of Greenhouse and Workable
application forms from a profile you save in its options page (kept in the browser only).

- Runs only on `boards.greenhouse.io`, `job-boards.greenhouse.io` and `apply.workable.com`.
  No LinkedIn, no Wuzzuf, no other site.
- Nothing happens until you press **Fill** in the bar it shows.
- It never submits, never clicks, never uploads a file, and leaves radios, checkboxes,
  dropdowns and free-text questions to you. A field you already filled is kept.
- Tests run against excerpts of real application pages (`test/fixtures`): `npm ci && npm test`.

Load unpacked from `chrome://extensions` (or `edge://extensions`) with Developer mode on.
