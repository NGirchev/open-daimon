---
paths:
  - "**/*.java"
---
# Project Security Rules

## Security Response Protocol

If security issue found:
1. STOP immediately
2. Use **security-reviewer** agent
3. Fix CRITICAL issues before continuing
4. Rotate any exposed secrets

## Project-Specific Rules

- API keys ONLY in environment variables (`System.getenv()`)
- Do not commit `application.yml` with real keys
- Use `@PreAuthorize` to protect REST endpoints (if Spring Security is added)
- Validate input with Jakarta Validation (`@Valid`, `@NotNull`, etc.)
- Never log passwords, tokens, or PII
- Error messages in API responses must not expose stack traces or internal paths
