---
description: Targeted Java bug-fix loop on a single service with a TDD-style failing-test gate; never commits.
argument-hint: <ServiceClass> <module>
---

# Fix Java Bug

Fix a bug in the Java service specified in: $ARGUMENTS

Expected format: `<ServiceClass> <module>` — e.g. `TelegramUserPriorityService opendaimon-telegram`.

## Rules

1. Do not touch any class other than the one named in $ARGUMENTS.
2. Do not move, rename, or delete test files.
3. Do not make git commits.
4. After each edit, run `./mvnw compile -pl <module>` — do not proceed until it passes.
5. Run only the specific failing test, not the full suite.

## Steps

1. Read the target class and summarize the current logic.
2. Write a failing unit test that demonstrates the bug. Show it to the user and wait for approval.
3. After approval, fix only the target class.
4. Run `./mvnw test -pl <module> -Dtest=<TestClass>` and show results.
5. Repeat steps 3–4 until the test passes.
6. Report what changed, the test name, and the result. Do not commit.
