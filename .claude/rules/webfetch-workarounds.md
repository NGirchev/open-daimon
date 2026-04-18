# Web Fetch Workarounds

## Rule 1: Validate URLs Before Fetching

Never pass placeholders or incomplete URLs to fetch tools. Reject a URL before tool use if it contains `...`, `…`, `<...>`, `{...}`, `TODO`, `example.com`, lacks an `http://` or `https://` scheme, or is otherwise syntactically incomplete. Ask the user for the real URL first.

## Rule 2: Prefer WebFetch Over JVM HTTP Tools

Use `WebFetch` before Java-backed HTTP helpers when fetching public web content. If a Java-backed tool returns a PKIX or truststore error, do not retry the same tool. Treat it as a local JVM truststore mismatch and switch to `WebFetch` when the domain is allowed.

## Rule 3: Respect The WebFetch Allowlist

Project-level `WebFetch` access is controlled by `.claude/settings.local.json`. If a domain is not allowed, stop and tell the user to add the exact permission line under `permissions.allow`:

```json
"WebFetch(domain:<host>)"
```

Do not bypass the allowlist silently. The user decides whether a new domain belongs in project permissions.

## Rule 4: Escalate WAF Errors To Playwright

For `403`, `429`, Cloudflare, WAF, bot-detection, or TLS failures from an allowed fetch path, use the real browser fallback:

1. `mcp__plugin_playwright_playwright__browser_navigate` to open the URL.
2. `mcp__plugin_playwright_playwright__browser_snapshot` to extract the accessible page tree.
3. Answer from the snapshot text.
4. `mcp__plugin_playwright_playwright__browser_close` when done.

A browser context usually avoids User-Agent and TLS fingerprint blocks that reject non-browser fetchers.

## Rule 5: Do Not Suggest curl Or wget Shortcuts

Do not propose `curl` or `wget` as the default workaround. In the current Claude settings, `wget` is denied globally and `curl` requires explicit approval, so shell fetch fallbacks waste time compared with `WebFetch` or Playwright.

## Rule 6: Do Not Apply System Truststore Fixes Automatically

If the user asks how to fix PKIX at the JVM level, explain the one-shot options, such as importing a certificate with `keytool -importcert` or using `-Djavax.net.ssl.trustStoreType=KeychainStore` on macOS. Do not execute those commands unless the user explicitly requests it because they have environment-wide side effects.
