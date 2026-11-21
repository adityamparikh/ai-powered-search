Closes #<!-- issue number: every PR traces to an issue -->

## What and why
<!-- One paragraph. Link the spec's acceptance criteria this meets. -->

## Review checklist
<!-- Reviewers (human or @claude) check each item; the author ticks only what is true. -->
- [ ] `./gradlew verify` is green locally (build, spotlessCheck, NullAway, keyless tests, checkLicense)
- [ ] New or changed behaviour has tests that assert results, not just that a mock was called
- [ ] Each acceptance criterion in the linked issue is met, or the gap is stated here
- [ ] Failure paths behave as the spec says (optional stages degrade with a WARN, never a 5xx)
- [ ] New RAG stages are behind a property that defaults to off
- [ ] No billed run was needed to prove this, or the `ragEval` / `liveTest` result is attached and was approved
- [ ] CLAUDE.md, README and `docs/` (including `docs/architecture.md` diagrams) updated if commands, config or a request path changed
- [ ] No secrets, `.env` files or real keys in the diff
