---
name: debug
description: "Root-cause debugging workflow for Java/Spring Boot in opendaimon-* modules — read logs first, pinpoint the cause before exploring the codebase, fix only the reported file, run just the failing test. Use when the user reports a bug, exception, stack trace, or unexpected behavior and provides logs or a failing test."
---

# Debugging Workflow

1. Read the error/logs the user provides — trust they are current.
2. Analyze the root cause BEFORE exploring the codebase. Do not explore aimlessly.
3. Propose a fix targeting ONLY the specific file/component mentioned.
4. After fixing, run the specific failing test — not the full suite.
5. Do not commit changes — just report results.
6. If the same issue persists after 2–3 fix attempts, stop and ask the user for guidance.
