---
description: Fetch public web content with WebFetch first and Playwright fallback for WAF or TLS failures.
---

# /fetch-web

## Purpose

Fetch and summarize arbitrary public web content using the project-safe path: validate the URL, use `WebFetch` when permitted, and fall back to Playwright only for recoverable browser-only failures.

## Usage

```
/fetch-web <url> [question]
```

Use `$ARGUMENTS` as the raw input. The first argument is the URL. The remaining text, if present, is the question to answer from the fetched page.

## Workflow

1. **Validate URL:** Reject placeholders and malformed URLs before using any tool. The URL must start with `http://` or `https://` and must not contain `...`, `…`, `<...>`, `{...}`, `TODO`, or `example.com`. If invalid, ask the user for the real URL and stop.
2. **Check allowlist:** Read `.claude/settings.local.json` and determine whether `permissions.allow` contains `WebFetch(domain:<host>)` for the URL host. If not allowed, tell the user to add this exact line and stop:

   ```json
   "WebFetch(domain:<host>)"
   ```

3. **Try WebFetch first:** If the domain is allowed, call `WebFetch` for the URL and summarize the result or answer the question.
4. **Use Playwright for recoverable fetch failures:** If `WebFetch` fails with `403`, `429`, Cloudflare, WAF, bot-detection, or TLS/PKIX-style errors, use:
   - `mcp__plugin_playwright_playwright__browser_navigate(url)`
   - `mcp__plugin_playwright_playwright__browser_snapshot()`
   - Extract the relevant page text from the snapshot.
   - `mcp__plugin_playwright_playwright__browser_close()` before returning.
5. **Do not use shell fetch shortcuts:** Do not switch to `curl` or `wget`. Use the configured Claude tools only.

## Output

Return a compact result with:

- Source URL
- Page title when available
- Key sections when visible
- At most 10 bullet points
- A direct answer to the question when one was provided

If the page cannot be fetched after the allowed fallback path, state the exact failure and what user action is required.
