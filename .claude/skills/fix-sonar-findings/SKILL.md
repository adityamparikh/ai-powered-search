---
name: fix-sonar-findings
description: Fetch the open SonarQube findings for ai-powered-search and fix them without changing behaviour, the way commits ff0b9eb and 50d8da4 did. Use when asked to "fix the Sonar issues", "clear the quality gate", "resolve code smells", or after a reformat or refactor moves code into Sonar's new-code window.
---

# Fix Sonar findings

The Gradle `sonar` task (plugin `org.sonarqube`, `build.gradle.kts`) analyses project key
`ai-powered-search` on the server in `SONAR_HOST_URL` (default `http://localhost:9000`) with
`SONAR_TOKEN`. Both must be in the environment; if they are not, ask the maintainer for them and
stop. Never print the token.

## 1. Analyse and list the findings

```bash
./gradlew test jacocoTestReport sonar      # coverage goes up with the analysis

# Quality gate and what failed it
curl -s -u "$SONAR_TOKEN:" "$SONAR_HOST_URL/api/qualitygates/project_status?projectKey=ai-powered-search"

# Open issues in new code (drop inNewCodePeriod for all of them)
curl -s -u "$SONAR_TOKEN:" \
  "$SONAR_HOST_URL/api/issues/search?componentKeys=ai-powered-search&resolved=false&inNewCodePeriod=true&ps=100" \
  | jq -r '.issues[] | "\(.severity) \(.rule) \(.component | sub("^ai-powered-search:"; "")):\(.line) \(.message)"'

# Security hotspots are separate from issues
curl -s -u "$SONAR_TOKEN:" "$SONAR_HOST_URL/api/hotspots/search?projectKey=ai-powered-search&status=TO_REVIEW"
```

Read the rule's description before fixing (`$SONAR_HOST_URL/coding_rules?open=<rule>`).

## 2. Fix

- Vulnerabilities first (they fail the gate), then bugs, then smells.
- Fix the code, never the rule: no `@SuppressWarnings("java:S...")`, no `// NOSONAR`, and no
  `sonar.exclusions` changes, unless the maintainer agrees the finding is a false positive; then
  mark it in Sonar instead and say why in the PR.
- Behaviour must not change. Typical fixes here: sanitise caller input before logging (S5145),
  narrow `throws Exception` to the real checked exceptions, `Stream.toList()`, pattern-matching
  `instanceof` (S6201), no eager `String.format` in log calls (S2629), one throwing call per
  `assertThrows` lambda in tests.
- NullAway still applies to every edited main-source line; Error Prone warnings
  (`[PatternMatchingInstanceof]` and friends) usually overlap with Sonar's smells.

## 3. Prove it

```bash
./gradlew spotlessApply
./gradlew verify
./gradlew sonar        # re-analyse; re-run the issue query and the quality gate check
```

Commit as `fix: resolve the Sonar findings ...` listing each rule and file in the body, as
ff0b9eb does. Fixes that introduce new smells (50d8da4 exists because of this) are caught by the
re-analysis: run it before opening the PR.
