# Coding Style And Conventions

- Follow AGENTS.md as the primary project rulebook.
- Language: code/comments/javadocs/docs in English; user-facing strings may vary by locale.
- Beans/services: do not rely on component scanning annotations for concrete services (`@Service`, `@Component`, `@Repository` classes). Register beans explicitly in module `config` classes via `@Bean` methods.
- Exception: JPA repository interfaces may use `@Repository`.
- Entities: reuse base entities from `opendaimon-common`; use inheritance strategy rules (User JOINED, Message SINGLE_TABLE with discriminator).
- Security: keys only via env vars, validate input with Jakarta Validation.
- Documentation: if behavior changes in modules with behavior docs (e.g., SPRING_AI_MODULE.md / TELEGRAM_MODULE.md), update those docs in same change.
- Testing expectations: unit tests (Mockito), repository integration tests (`@DataJpaTest` + Testcontainers), verify critical logic coverage expectations from AGENTS.md.