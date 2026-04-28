#!/usr/bin/env bash
# PreToolUse hook for the built-in `WebFetch` tool.
#
# Goal: force Claude to use `/fetch-web` (which routes through Playwright)
# for any host not on a short safe list. The previous "rules + slash command"
# approach relied on Claude reading the rules. This is the harness-level
# guard that runs deterministically before every WebFetch call.
#
# stdin (JSON):
#   {"tool_name":"WebFetch","tool_input":{"url":"https://...","prompt":"..."},...}
#
# stdout:
#   - For safe hosts: nothing, exit 0 → Claude Code proceeds with WebFetch.
#   - For blocked hosts: a permissionDecision=deny JSON payload, exit 0 →
#     Claude Code refuses the call and shows the reason to Claude.

set -euo pipefail

payload=$(cat)

parse_script='
import json, sys
from urllib.parse import urlparse
try:
    data = json.loads(sys.argv[1])
except Exception:
    print("", "")
    sys.exit(0)
url = (data.get("tool_input") or {}).get("url", "") or ""
host = (urlparse(url).hostname or "") if url else ""
print(url, host)
'

read -r url host < <(python3 -c "$parse_script" "$payload" || echo "")

# If we could not parse a URL, stay out of the way.
if [[ -z "${url:-}" || -z "${host:-}" ]]; then
  exit 0
fi

# Safe hosts — mirror of WebFetch allowlist in .claude/settings.local.json.
# Keep this list short; for anything else, /fetch-web handles it.
safe_hosts=(
  github.com
  raw.githubusercontent.com
  api.github.com
  core.telegram.org
  openrouter.ai
  search.maven.org
  java.testcontainers.org
  testcontainers.com
  hub.docker.com
)

for safe in "${safe_hosts[@]}"; do
  if [[ "$host" == "$safe" || "$host" == *".$safe" ]]; then
    exit 0
  fi
done

# Not on the safe list — deny with a redirect to /fetch-web.
deny_script='
import json, sys
url, host = sys.argv[1], sys.argv[2]
reason = (
    f"WebFetch is blocked for host {host!r} by .claude/hooks/webfetch-guard.sh. "
    "This host is not on the short safe allowlist and is known to fail in this "
    "environment (JetBrains MCP Ktor client gives PKIX on modern TLS chains; "
    "Medium / itnext.io / ResearchGate return HTTP 403 from Cloudflare/WAF). "
    f"Invoke the slash command `/fetch-web {url}` instead - it uses Playwright "
    "(real Chromium), which bypasses both problems. If you genuinely need raw "
    f"WebFetch here, add WebFetch(domain:{host}) to .claude/settings.local.json "
    "AND add the host to safe_hosts in .claude/hooks/webfetch-guard.sh."
)
print(json.dumps({
    "hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "deny",
        "permissionDecisionReason": reason,
    }
}))
'

python3 -c "$deny_script" "$url" "$host"
