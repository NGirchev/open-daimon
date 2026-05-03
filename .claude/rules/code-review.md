# Code Review Standards

## Security Checkpoints

When code touches authentication, authorization, user input, database queries, file system operations, external API calls, cryptographic operations, or payment flows — review it against `.claude/rules/java/security.md` before approving. If a clear CRITICAL issue is found (injection, secrets leak, auth bypass, data loss), stop and flag it before continuing with other changes.

## Review Severity Levels

| Level | Action |
|-------|--------|
| CRITICAL | **BLOCK** — security vulnerability or data loss risk |
| HIGH | **WARN** — bug or significant quality issue |
| MEDIUM | **INFO** — maintainability concern |
| LOW | **NOTE** — style or minor suggestion |

## Approval Criteria

- **Approve**: no CRITICAL or HIGH issues.
- **Block**: CRITICAL issues found.
