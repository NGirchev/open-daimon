# Code Review Standards

## Security Review Triggers

**STOP and use security-reviewer agent when:**
- Authentication or authorization code
- User input handling or database queries
- File system operations or external API calls
- Cryptographic operations or payment code

## Review Severity Levels

| Level | Action |
|-------|--------|
| CRITICAL | **BLOCK** — security vulnerability or data loss risk |
| HIGH | **WARN** — bug or significant quality issue |
| MEDIUM | **INFO** — maintainability concern |
| LOW | **NOTE** — style or minor suggestion |

## Approval Criteria

- **Approve**: No CRITICAL or HIGH issues
- **Block**: CRITICAL issues found
