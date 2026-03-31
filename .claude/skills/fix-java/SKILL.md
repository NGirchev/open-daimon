## Java Bug Fix Workflow

Replace `<ServiceClass>` and `<module>` with the actual class name and Maven module before starting.

### Rules
1. Do NOT touch any other classes besides `<ServiceClass>`
2. Do NOT move, rename, or delete any test files
3. Do NOT make any git commits
4. After each edit, run `./mvnw compile -pl <module>` — do NOT proceed until it passes
5. Run only the specific failing test, not the full suite

### Steps
1. Read `<ServiceClass>` and understand the current logic
2. Write a **failing** unit test that demonstrates the bug — show it to the user and wait for approval
3. After approval, fix only `<ServiceClass>`
4. Run `./mvnw test -pl <module> -Dtest=<TestClass>` and show results
5. Repeat steps 3-4 until the test passes
6. Report results — do NOT commit
