# Task Completion Checklist

- Run `mvn clean compile -pl opendaimon-app -am` after code/config edits.
- If behavior changed, run relevant module tests (`mvn clean test -pl <module>` or targeted tests).
- Ensure docs are updated when behavior docs apply (module-level behavior references).
- Verify no secrets or hardcoded keys were introduced.
- Confirm logging and error messages remain in English for code-level outputs.
- Review diffs for module-style consistency before handing off.