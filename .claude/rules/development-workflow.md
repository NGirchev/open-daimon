# Development Workflow

## Available Agents

Located in `~/.claude/agents/`:

| Agent | Purpose | When to Use |
|-------|---------|-------------|
| planner | Implementation planning | Complex features, refactoring |
| architect | System design | Architectural decisions |
| tdd-guide | Test-driven development | New features, bug fixes |
| code-reviewer | Code review | After writing code |
| security-reviewer | Security analysis | Before commits |
| build-error-resolver | Fix build errors | When build fails |
| java-reviewer | Java/Spring Boot review | Java projects |

**Immediate agent usage** (no user prompt needed):
1. Complex feature requests → **planner**
2. Code just written/modified → **code-reviewer**
3. Bug fix or new feature → **tdd-guide**
4. Architectural decision → **architect**

ALWAYS use parallel execution for independent agent operations.

## Feature Implementation Workflow

0. **Research & Reuse** _(mandatory before any new implementation)_
   - **GitHub code search first:** `gh search repos` and `gh search code` for existing implementations
   - **Library docs second:** Use Context7 or vendor docs to confirm API behavior
   - **Check package registries** before writing utility code
   - Prefer adopting a proven approach over writing net-new code

1. **Plan First**
   - Use **planner** agent to create implementation plan
   - Identify dependencies and risks, break down into phases

2. **TDD Approach**
   - Use **tdd-guide** agent
   - Write tests first (RED) → Implement (GREEN) → Refactor (IMPROVE)
   - Verify 80%+ coverage

3. **Code Review**
   - Use **code-reviewer** agent immediately after writing code
   - Address CRITICAL and HIGH issues

4. **Commit & Push**
   - Follow conventional commits format
   - See [git-workflow.md](git-workflow.md) for commit message format and PR process

5. **Pre-Review Checks**
   - All CI/CD checks passing, merge conflicts resolved
   - Branch up to date with target branch
