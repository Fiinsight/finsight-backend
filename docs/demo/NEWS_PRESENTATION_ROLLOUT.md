# News presentation rollout review (2026-11-03)

No merge, deploy or production writes were performed for this change.
LLM flags remain false. This release is independent of chart latency PR #173.

## Changes
- Briefing responses contain at most two sentences / 150 characters, without evidence labels or bracketed six-digit codes. Detailed normal reading keeps evidence labels for frontend badges.
- Publisher image references come only from RSS media/enclosure or publisher og:image metadata. V21 adds a nullable image_url column. No image file is downloaded, copied or rehosted. Missing/failed references use a category background.
- Market overview classification precedes industry keyword classification, including existing articles at response time.
- Legacy neutral/neutral partial feedback is corrected for display only; stored judgements are unchanged.
- AI investment points pair a topic-relevant source quote with one checkpoint. The backend AI pin is a separate commit.

## Approval stages
1. Review and merge AI, backend and frontend PRs in that order (separate approval).
2. Approve backend deployment and V21 schema addition, then frontend export/Hosting deployment. Keep all LLM flags false. Verify new collection saves only publisher URLs; verify card, detail badges and recent/unread result banner.
3. Review the read-only recent-seven-day backfill counts and three examples, refresh dry-run immediately before applying, then obtain explicit apply approval.

## Read-only backfill commands
Use the existing secure DB_URL/DB_USERNAME/DB_PASSWORD environment; do not put secret values in command history or reports.

AI repository (default dry-run):
```sh
USE_REAL_LLM=false REWRITE_WITH_LLM=false DOCENT_WITH_LLM=false FEEDBACK_WITH_LLM=false \
  python -m scripts.rewrite_news_batch --excerpt --since '<reviewed UTC cutoff>' --limit 1000
```
The exact inspected cutoff, article IDs and generated before/after text are in the local review artifacts. The maintenance command selects candidates; the separately generated preview runs the same rewrite function with allow_llm=False and performs no DB writes. Review again if articles/code change. Do not run --apply until approved.

Backend repository (default dry-run):
```sh
python scripts/backfill_news_images.py --plan '<reviewed image-url-dry-run.json>'
```
The reviewed plan contains id, source url, timezone-aware publishedAt, previous image URL and proposed metadata URL. The tool checks the current row, seven-day window, EXCERPT mode and previous value. It refuses apply before V21. After explicit approval only, add `--apply --limit <approved count>`; no network/image fetching happens in this maintenance script.

## Verification
- AI: 384 passed, zero failures.
- Backend: 342 tests, zero failures/errors/skips, including V21 on disposable PostgreSQL and Redis; Python image-backfill tests are part of Gradle test.
- Frontend: 179 passed, tsc --noEmit and Expo web export passed.
- Mobile before: authenticated production QA account. After: authenticated local preview with real article copies; external KIS credentials were not copied, so local market tiles can show unavailable. No production quiz/judgement records were created.
- Rollback: redeploy prior backend/frontend commits and prior AI pin; V21 is additive and can remain. A later approved content backfill requires a separate pre-write content snapshot to reverse; do not drop schema or overwrite article content as part of this review.

## Follow-up review: release baseline, source links and category content
All three repositories' `release/demo-2026-11` trees equal the main trees used to start these PRs (frontend 84e0b414, backend 9be21869, AI e1c546b5). The branches contain those release commits; no release changes were omitted. Keep main as the reviewed production PR target.

The frontend thumbnail opens the publisher's original article, with a category icon and colored background when no usable image exists. This opens an external URL only and does not download/rehost images.

The existing rewrite backfill updates the stored three reading-level excerpts and investment points; compact card summaries are derived at response time. It does not need a separate card-summary database column. Categories are stored separately, and have their own guarded reviewed-plan tool:
```sh
python scripts/backfill_news_categories.py --plan '<reviewed category-backfill-plan.json>'
```
The plan is produced by the exact Java classifier against the read-only article snapshot. Dry-run is default; apply requires `--apply --limit <approved count>` after explicit approval. It checks id, source URL, publication time, EXCERPT scope, seven-day window and previous category, and skips changed rows. Use a fresh read-only snapshot/dry-run after deployment before requesting apply approval. No existing production category was changed by this PR.

At snapshot 2026-11-03 03:22:17 UTC, the 134 EXCERPT articles were all from Yonhap: metadata image URL 131/134 (97.76%). Other publishers had no samples, so no acquisition rate is claimed. Re-running collection may yield different counts or URLs. Frozen classifier plan: 8 category changes / 126 unchanged (snapshot comparison, not a live category apply result).
