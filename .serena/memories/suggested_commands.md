# Suggested Commands (Darwin/macOS)

## Build / Verify
- `mvn clean compile -pl opendaimon-app -am` (preferred compile verification after edits)
- `mvn clean install`
- `mvn clean package -DskipTests`

## Tests
- `mvn clean test`
- `mvn clean test -pl <module>`
- `mvn clean test -Dtest=<TestClass> -pl opendaimon-app`

## Run
- `mvn spring-boot:run -pl opendaimon-app`
- `docker-compose up -d`
- `docker-compose logs -f opendaimon-app`

## DB / Infra
- `mvn flyway:migrate`
- `mvn flyway:info`
- `docker-compose ps`

## Useful local utilities
- `git status`, `git diff`
- `ls`, `find`, `rg` (ripgrep), `sed`, `tail -f`
- `docker`, `docker-compose`