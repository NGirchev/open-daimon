#!/bin/bash
# Stop Hook: in-terminal bell (codex-style) + optional macOS notification.
#
# Default: writes BEL to /dev/tty so the terminal tab pings the user
# (visual bell in Terminal.app, audible/visual in iTerm, etc). iTerm also
# gets an in-window banner via OSC 9.
#
# Set CLAUDE_NOTIFY_SYSTEM=1 to additionally send a macOS Notification Center
# banner via terminal-notifier.
#
# Exit 0 always (non-blocking).

[[ "$(uname)" != "Darwin" ]] && exit 0

INPUT=$(cat)

SUMMARY=$(python3 - <<'PY' 2>/dev/null
import json, sys

raw = sys.stdin.read()
try:
    data = json.loads(raw) if raw.strip() else {}
except Exception:
    data = {}

transcript = data.get("transcript_path") or ""

def extract_text(entry):
    msg = entry.get("message") or {}
    content = msg.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        for block in content:
            if isinstance(block, dict) and block.get("type") == "text":
                text = block.get("text") or ""
                if text.strip():
                    return text
    return ""

summary = "Done"
if transcript:
    try:
        last_text = ""
        with open(transcript, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    entry = json.loads(line)
                except Exception:
                    continue
                if entry.get("type") != "assistant":
                    continue
                text = extract_text(entry)
                if text:
                    last_text = text
        for l in last_text.split("\n"):
            s = l.strip()
            if s:
                summary = s[:100]
                break
    except Exception:
        pass

print(summary)
PY
<<< "$INPUT")

SAFE_SUMMARY="${SUMMARY//\"/\\\"}"

# Primary: codex-style bell in the terminal tab that hosts `claude`.
# Stop hooks run as a detached subprocess with no controlling TTY, so
# /dev/tty is unusable. Walk up the process tree to find the first
# ancestor bound to a real TTY and write the bell there directly.
find_ancestor_tty() {
  local pid=$$
  local i=0
  while [ "${pid:-1}" -gt 1 ] && [ "$i" -lt 10 ]; do
    local t
    t=$(ps -o tty= -p "$pid" 2>/dev/null | tr -d ' ')
    if [ -n "$t" ] && [ "$t" != "??" ] && [ "$t" != "-" ]; then
      echo "/dev/$t"
      return 0
    fi
    pid=$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ')
    i=$((i + 1))
  done
  return 1
}

TARGET_TTY=$(find_ancestor_tty)
if [ -n "$TARGET_TTY" ] && [ -w "$TARGET_TTY" ]; then
  {
    printf '\a'
    # iTerm2 proprietary OSC 9 — in-window banner with message text.
    # Other terminals ignore it silently.
    if [ -n "$ITERM_SESSION_ID" ] || [ "$TERM_PROGRAM" = "iTerm.app" ]; then
      printf '\033]9;%s\007' "$SAFE_SUMMARY"
    fi
  } > "$TARGET_TTY" 2>/dev/null
fi

# Optional: macOS Notification Center banner, opt-in via env var.
if [ "${CLAUDE_NOTIFY_SYSTEM:-0}" = "1" ] && command -v terminal-notifier >/dev/null 2>&1; then
  terminal-notifier \
    -title "Claude Code" \
    -message "${SAFE_SUMMARY}" \
    -sender com.apple.Terminal \
    -group "claude-code-stop" \
    >/dev/null 2>&1
fi

exit 0
