---
name: tailoring-eval
description: Check CV tailoring against golden postings for invented content and page length.
disable-model-invocation: true
---
1. Run the tailoring worker on every posting in src/test/resources/golden-postings/.
2. For each output, fail if: a bullet ID does not exist in the profile, a skill or number
   appears that is not in the profile, or the PDF is not exactly one page.
3. Print a table: posting, pass/fail, ATS score, reason for any failure.
