# Contributing

Thank you for your interest in contributing to AI Bot. We welcome contributions.

**Language**: This project uses **English** for all documentation, code comments, commit messages, and issue discussions. Please submit all contributions, bug reports, and comments in English so they can be understood and reviewed by the global developer community.

## Development

### Requirements

- Java 21 (LTS)
- Maven 3.6+
- Docker (for integration tests with Testcontainers)

### Setup

```bash
git clone https://github.com/NGirchev/ai-bot.git
cd ai-bot
mvn clean install
```

Copy [.env.example](.env.example) to `.env` and set the required variables if you run the application locally. Do not commit `.env`.

### Making changes

1. Create a branch: `git checkout -b feature/your-feature-name`
2. Make your changes following the [Code style and conventions](#code-style-and-conventions) below
3. Run tests: `mvn test` (or `mvn test -pl <module>` for a single module)
4. Commit and push: `git push origin feature/your-feature-name`
5. Open a Pull Request on GitHub

All tests must pass before submitting a Pull Request. Integration tests use Testcontainers and require Docker.

## Code style and conventions

Follow the project conventions described in **[AGENTS.md](AGENTS.md)**. Summary:

- **Beans**: Do not use `@Service`, `@Component`, or `@Repository` on service/component classes. Register beans explicitly in configuration classes via `@Bean` methods. Configuration classes live in the `config` package of each module.
- **Package structure**: `io.github.ngirchev.aibot.<module-name>.<layer>` (e.g. `io.github.ngirchev.aibot.telegram.service`).
- **Entities**: Base entities only in `aibot-common`; module-specific entities extend them. Do not duplicate entities across modules.
- **Dependencies in pom.xml**: Follow the order defined in the parent POM (project modules, Spring, database, other, test). All versions in `<properties>`.
- **Language**: Code, comments, JavaDoc, and commit messages in English. User-facing strings (i18n, bot messages) may be in any language.

## Testing

- **Unit tests** for services (Mockito).
- **Integration tests** for repositories and flows (Testcontainers).
- **Coverage**: At least 70% for critical business logic.
- **Do not mock entities** — use real objects where possible.
- **Repository tests**: Use `@DataJpaTest` where appropriate.

When adding or changing behaviour, add or update tests so that the change is covered and existing tests still pass.

### Running tests

```bash
# All modules
mvn test

# Single module (and its dependencies)
mvn test -pl aibot-telegram
mvn test -pl aibot-spring-ai -Dtest=SpringAIGatewayIT
```

See [README — Testing](README.md#testing) for more examples (including Windows).

## Pull request requirements

Before submitting a Pull Request, ensure:

1. All tests pass (`mvn test` or equivalent for the changed modules).
2. Code follows the style and conventions in [AGENTS.md](AGENTS.md).
3. New or changed behaviour is covered by tests.
4. JavaDoc is added or updated for public APIs where relevant.
5. No secrets or API keys are committed (use environment variables or `.env`).

## Security

- **API keys and secrets**: Only in environment variables or `.env` (and `.env` must not be committed).
- **Do not commit** real credentials in `application.yml` or in code.
- **Reporting vulnerabilities**: If you discover a security vulnerability, do not open a public issue. Report it privately via [GitHub Security Advisories](https://github.com/NGirchev/ai-bot/security/advisories/new) or contact the maintainers.

## Releasing (maintainers)

Releases are published to Maven Central. To run a release from GitHub Actions you need two repository secrets.

### GitHub Actions secrets for GPG signing

Add these in the repository: **Settings → Secrets and variables → Actions → New repository secret**.

| Secret name       | Description | How to get the value |
|-------------------|-------------|------------------------|
| **GPG_PRIVATE_KEY** | GPG private key used to sign artifacts (armored ASCII). | Run locally: `gpg --export-secret-keys --armor YOUR_KEY_ID` and paste the full output (including `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `-----END PGP PRIVATE KEY BLOCK-----`). Use the same key you use for Maven Central. |
| **GPG_PASSPHRASE**  | Passphrase for that GPG key. | The passphrase you set when creating the key. Used by Maven GPG plugin during `deploy -P release`. |

Also configure **Central Portal** credentials in GitHub secrets if the release workflow deploys from CI (e.g. `CENTRAL_TOKEN_USERNAME`, `CENTRAL_TOKEN_PASSWORD` from [Sonatype Central Portal](https://central.sonatype.com/) → User Token), or run `mvn deploy -P release` locally with `~/.m2/settings.xml` configured.

### Manual release (local)

```bash
mvn -B release:prepare -Darguments="-DskipTests"
git checkout v1.0.0   # use the tag created
mvn clean deploy -P release -DskipTests
git push origin master
git push --tags
```

## Documentation

- **[AGENTS.md](AGENTS.md)** — Architecture, module structure, code style, and rules for contributors and AI agents.
- **[README.md](README.md)** — Quick start, build, run, testing, and modules.
- **[DEPLOYMENT.md](DEPLOYMENT.md)** — Production deployment.
- **[MODULAR_MIGRATIONS.md](docs/MODULAR_MIGRATIONS.md)** — Flyway modular migrations.

## Questions and issues

- **Bug reports and feature requests**: [GitHub Issues](https://github.com/NGirchev/ai-bot/issues).
- **Questions**: Open an issue or a discussion on GitHub.

Please write issues and comments in English.
