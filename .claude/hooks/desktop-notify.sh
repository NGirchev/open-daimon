#!/bin/bash
# Stop Hook: macOS desktop notification when Claude finishes responding.
# Exit 0 always (non-blocking).

[[ "$(uname)" != "Darwin" ]] && exit 0

INPUT=$(cat)

SUMMARY=$(python3 -c "
import json, sys
try:
    data = json.loads(sys.stdin.read())
    msg = data.get('last_assistant_message', '') or ''
    for line in msg.split('\n'):
        line = line.strip()
        if line:
            print(line[:100])
            sys.exit(0)
    print('Done')
except Exception:
    print('Done')
" <<< "$INPUT" 2>/dev/null)

SAFE_SUMMARY="${SUMMARY//\"/\u201C}"
osascript -e "display notification \"${SAFE_SUMMARY}\" with title \"Claude Code\"" 2>/dev/null
exit 0
