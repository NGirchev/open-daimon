---
description: Fetch a public web page via WebFetch, falling back to a real browser (Playwright) on 403 / WAF / TLS errors.
---

# /fetch-web

## Purpose

Fetch and summarize a public web page, surviving the three usual failure
modes seen in this repo: placeholder URLs, WebFetch domain-allowlist
rejection, and WAF 403s on sites like ResearchGate / Medium / itnext.io.

This command is also the **default escape hatch** for the PreToolUse guard
at `.claude/hooks/webfetch-guard.sh` — when Claude tries a raw `WebFetch`
for a host off the safe list, the hook denies with a reason pointing
here, so Claude should retry via `/fetch-web` in the next turn.

See also: `.claude/rules/webfetch-workarounds.md` for the full rule set.

## Usage

```
/fetch-web <url> [question]
```

- `<url>` — full `http(s)://` URL. No placeholders. No ellipses.
- `[question]` — optional. If set, return an answer grounded in the page
  text. If omitted, return a general summary.

Example:

```
/fetch-web https://itnext.io/some-article "What was the Quarkus startup time?"
```

Arguments: `$ARGUMENTS`

## Workflow

### Step 1 — Validate the URL

Inspect the first whitespace-delimited token of `$ARGUMENTS`:

- If empty, or if it contains `…` (U+2026), `<`, `>`, `{`, `}`, `TODO`,
  or does not start with `http://` / `https://` — **stop and ask the user
  for a real URL**. Do not call any tool. Cite Rule 1 of
  `webfetch-workarounds.md`.

### Step 2 — Try WebFetch first

Call the built-in `WebFetch` with the URL and (if provided) the question
as the prompt. This is cheapest and gives the cleanest markdown back.

If it returns content, go to Step 5.

### Step 3 — Handle domain-not-allowed

If `WebFetch` is rejected because the domain is not in the project
allowlist, do **not** silently fall through to Playwright. Tell the user
which line to add to `.claude/settings.local.json`, e.g.:

```json
"permissions": {
  "allow": [
    "WebFetch(domain:<host>)"
  ]
}
```

Wait for them to add it and re-run `/fetch-web`. Alternatively, if they
confirm they want to skip the allowlist entirely for this URL, proceed
straight to Step 4 (browser fallback).

### Step 4 — On 403 / 429 / WAF / TLS errors, switch to Playwright

For any of these failure shapes:

- HTTP 403 / 429 / 503 with a challenge page
- `PKIX path building failed` (JVM fetch path, per Rule 2)
- An obvious WAF / bot-detection body (`Attention Required`, `Checking
  your browser`, Cloudflare Ray ID, etc.)

Run this exact sequence (close the browser even on error):

1. `mcp__plugin_playwright_playwright__browser_navigate` with `{ "url": "<url>" }`.
2. If the page shows a challenge, wait a few seconds and take
   `mcp__plugin_playwright_playwright__browser_snapshot` — most challenges
   auto-solve after JS runs.
3. Read the snapshot text. If `[question]` was given, answer from the
   snapshot. Otherwise, summarize.
4. `mcp__plugin_playwright_playwright__browser_close`.

### Step 5 — Return a compact summary

- **Title** of the page (first `<h1>` or `<title>`).
- **Source URL**.
- **Summary** — 3–8 bullets. Focus on what the user asked (if
  `[question]` was provided) or the page's main points.
- **Notable numbers / quotes** — at most 3, with the surrounding sentence.
- **How it was fetched** — one of `WebFetch`, `Playwright fallback`, or
  `WebFetch + Playwright retry`. Helps the user see when the allowlist or
  a WAF was involved.

Do **not** paste the full page. Do **not** loop retrying on the same
failure shape — if Playwright also fails, report why and stop.

## Rules inherited from `.claude/rules/webfetch-workarounds.md`

- Never propose `curl` / `wget` — denied globally.
- Never execute JVM truststore fixes — surface the commands for the user.
- Never silently widen the WebFetch allowlist — ask.
