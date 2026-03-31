# Documentation Index

## Project Documentation

- [AGENTS.md](AGENTS.md) — AI agent role, user profile, and coding rules (context-efficient)
- [ARCHITECTURE.md](ARCHITECTURE.md) — Module structure, database design, architectural patterns, and flows
- [CODE_STYLE.md](CODE_STYLE.md) — Java code conventions, Spring Bean configuration, entity guidelines
- [Makefile](Makefile) — Build, test, database, and quality commands

## Quick Commands

```bash
# Build & test
make build                           # Full build with tests
make test                            # Run all tests
make test-module MODULE=opendaimon-spring-ai  # Test one module

# Database
make db-migrate                      # Run Flyway migrations
make db-info                         # Show migration status

# Run
make run                             # Local development
make run-docker                      # Full Docker deployment
```
