# Code Review Standards

## When to Review

**MANDATORY review triggers:**
- After writing or modifying code
- Before any commit to shared branches
- When security-sensitive code is changed (auth, payments, user data)
- When architectural changes are made
- Before merging pull requests

**Pre-Review Requirements:**
- All automated checks (CI/CD) are passing
- Merge conflicts are resolved
- Branch is up to date with target branch

## Review Checklist

Before marking code complete:
- [ ] Code is readable and well-named
- [ ] Functions are focused (<50 lines)
- [ ] Files are cohesive (<800 lines)
- [ ] No deep nesting (>4 levels)
- [ ] Errors are handled explicitly
- [ ] No hardcoded secrets or credentials
- [ ] No System.out.println or debug logging left in production code
- [ ] Tests exist for new functionality
- [ ] Test coverage meets 80% minimum

## Security Review Triggers

**STOP and use security-reviewer agent when:**
- Authentication or authorization code
- User input handling or database queries
- File system operations or external API calls
- Cryptographic operations or payment code

For detailed security checklist, see [java/security.md](java/security.md).

## Review Severity Levels

| Level | Action |
|-------|--------|
| CRITICAL | **BLOCK** — security vulnerability or data loss risk |
| HIGH | **WARN** — bug or significant quality issue |
| MEDIUM | **INFO** — maintainability concern |
| LOW | **NOTE** — style or minor suggestion |

## Agent Usage

| Agent | Purpose |
|-------|---------|
| **code-reviewer** | General code quality, patterns, best practices |
| **security-reviewer** | Security vulnerabilities, OWASP Top 10 |
| **java-reviewer** | Java/Spring Boot specific issues |
| **database-reviewer** | SQL, JPA, database schema issues |

## Approval Criteria

- **Approve**: No CRITICAL or HIGH issues
- **Warning**: Only HIGH issues (merge with caution)
- **Block**: CRITICAL issues found

Related rules: [java/security.md](java/security.md), [java/testing.md](java/testing.md), [git-workflow.md](git-workflow.md)
