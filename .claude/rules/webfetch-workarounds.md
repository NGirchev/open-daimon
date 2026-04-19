# Web Fetch Workarounds

Operational rules for fetching web content in this repository. Written after a
session where three consecutive web-fetch calls failed due to (1) an ellipsis
placeholder leaking into the URL, (2) a PKIX truststore mismatch in a
JVM-backed fetch path, and (3) a Cloudflare 403 on ResearchGate.

## Rule 1 — Never pass placeholders as URLs

Reject a fetch request whose URL is empty, contains the ellipsis character
`…` (U+2026), angle-bracket placeholders (`<...>`), curly-brace placeholders
(`{...}`), the literal `TODO`, `example.com`, or is otherwise not a concrete
`http(s)://` address. Ask the user for the real URL before calling any tool.

Passing a placeholder produces `Invalid URL. Must start with http:// or
https://` and burns a tool turn.

## Rule 2 — Prefer the built-in WebFetch over any JVM-based HTTP tool

A `PKIX path building failed` error always originates in a JVM HTTP client
(JetBrains MCP bridge, a Java plugin helper, or similar). It means the tool's
cacerts truststore does not trust the server's certificate chain.

- **Do not retry the same JVM call.** PKIX is a truststore mismatch, not a
  transient failure. Retrying wastes tokens.
- **Switch to `WebFetch` immediately.** It routes through Anthropic's edge,
  not a local JVM, and is not affected by truststore state.
- If `WebFetch` also fails for the same URL, escalate per Rule 4.

## Rule 3 — Respect the WebFetch domain allowlist

`WebFetch` in this project is restricted to an explicit allowlist in
`.claude/settings.local.json` under `permissions.allow`. If a requested
domain is not on the list, `WebFetch` is rejected at the permission layer
before any network call is made — retrying without changing settings will
never succeed.

When a user asks to fetch an unlisted domain:

1. Tell them which domain is missing.
2. Show them the exact line to add, e.g.
   `"WebFetch(domain:itnext.io)"`.
3. Wait for them to add it. Do not try to bypass the allowlist via other
   tools unless they explicitly choose Rule 4.

## Rule 4 — On 403 / 429 / WAF, escalate to Playwright

For sites behind bot-detection (Cloudflare, Akamai, PerimeterX) a plain HTTP
fetch is blocked by User-Agent or JA3 fingerprint — including `WebFetch`.
A real browser bypasses most of this because it produces a genuine fingerprint.

Playwright MCP is enabled in `.claude/settings.json`. Preferred sequence:

1. `mcp__plugin_playwright_playwright__browser_navigate` with the URL.
2. `mcp__plugin_playwright_playwright__browser_snapshot` to read the
   accessible tree as text.
3. Extract the needed answer or summary from the snapshot.
4. `mcp__plugin_playwright_playwright__browser_close` to free the browser.

The `/fetch-web` slash command automates this fallback — prefer invoking it
over re-implementing the sequence ad hoc.

## Rule 5 — Never propose curl or wget

`curl` and `wget` are denied globally in `~/.claude/settings.json`
(`Bash(*curl *)`, `Bash(*wget *)`). Proposing them produces a denied
permission prompt and wastes the user's attention. Use `WebFetch` or
Playwright instead.

## Rule 6 — Do not execute JVM truststore fixes

If the user asks to fix PKIX at the JVM level, explain the options but do
not run them — they are environment-wide side effects outside this
repository:

- `keytool -importcert -alias <name> -file <cert.pem> -keystore "$JAVA_HOME/lib/security/cacerts"`
- JVM flag `-Djavax.net.ssl.trustStoreType=KeychainStore
  -Djavax.net.ssl.trustStore=NONE` to delegate to the macOS Keychain.

Let the user decide and execute these themselves.

## Quick decision table

| Symptom | Action |
|---|---|
| URL contains `…` / `<...>` / `{...}` / `TODO` | Stop, ask for the real URL (Rule 1). |
| `PKIX path building failed` | Switch to `WebFetch` (Rule 2). |
| `WebFetch` rejected — domain not allowed | Ask user to add `WebFetch(domain:<host>)` (Rule 3). |
| HTTP 403 / 429 / WAF challenge page | Use `/fetch-web` or invoke Playwright directly (Rule 4). |
| Tempted to use `curl`/`wget` | Don't (Rule 5). |
| User asks to fix JVM cacerts | Show commands, don't run them (Rule 6). |

## Automatic guard

Rules above describe what Claude *should* do; the PreToolUse hook at
`.claude/hooks/webfetch-guard.sh` is what the Claude Code harness *will*
do before every `WebFetch` call. If the URL's host is not in the
hook's `safe_hosts` array, the harness returns a `permissionDecision:
deny` with a reason pointing at `/fetch-web <url>`. Claude then sees
that reason as a tool error and invokes `/fetch-web` in the next turn.

Consequences for day-to-day work:

- **Do not retry the same `WebFetch` call** after a deny — it will deny
  again. Read the `permissionDecisionReason` and run `/fetch-web` as it
  instructs.
- **Editing the allowlist is a two-line change**:
  `.claude/settings.local.json` gets a new `"WebFetch(domain:<host>)"`
  entry under `permissions.allow`, **and** the host is added to
  `safe_hosts` in `.claude/hooks/webfetch-guard.sh`. Changing only one
  of the two leaves the guard inconsistent with the permission layer.
- **The guard never makes a network call** and has no side effects —
  if its logic ever feels wrong, pipe a sample payload into it locally
  to see the deny reason.

## JVM truststore fix (Corretto 21, JetBrains MCP)

The PKIX error originates in the JetBrains MCP plugin running inside
IntelliJ IDEA Community 2025.2 (JAR at
`~/Library/Application Support/JetBrains/IdeaIC2025.2/plugins/mcpserver/lib/mcpserver.jar`,
JVM at `~/Library/Java/JavaVirtualMachines/corretto-21.0.8`). Its
cacerts lags the current Let's Encrypt / Cloudflare chains.

Per **Rule 6** Claude never runs these — surface them to the user:

```sh
# 1. Back up the current truststore
sudo cp ~/Library/Java/JavaVirtualMachines/corretto-21.0.8/Contents/Home/lib/security/cacerts \
        ~/Library/Java/JavaVirtualMachines/corretto-21.0.8/Contents/Home/lib/security/cacerts.bak

# 2. Dump every trusted CA from the macOS System keychain into a PEM bundle
security find-certificate -a -p /Library/Keychains/System.keychain > /tmp/macos-system-ca.pem

# 3. Import the bundle into Corretto's truststore
sudo keytool -importcert -trustcacerts -alias macos-system-ca \
  -file /tmp/macos-system-ca.pem -noprompt -storepass changeit \
  -keystore ~/Library/Java/JavaVirtualMachines/corretto-21.0.8/Contents/Home/lib/security/cacerts

# 4. Restart IntelliJ so the JetBrains MCP plugin picks up the new cacerts
```

An alternative is delegating to the macOS Keychain at JVM startup
(`-Djavax.net.ssl.trustStoreType=KeychainStore
-Djavax.net.ssl.trustStore=NONE`), but injecting JVM flags into a
JetBrains plugin is awkward — prefer the keytool import above.

Even after this fix, the WebFetch guard still redirects Medium / Cloudflare-fronted
hosts to `/fetch-web`, because those sites 403 any non-browser user-agent
regardless of TLS state.
