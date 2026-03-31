## Debugging Workflow
1. Read the error/logs the user provides — trust they are current
2. Analyze the root cause BEFORE exploring the codebase
3. Propose a fix targeting ONLY the specific file/component mentioned
4. After fixing, run the specific failing test, not the full suite
5. Do NOT commit changes — just report results
