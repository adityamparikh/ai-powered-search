---
name: Spec
about: A change to build. Every section is filled in before work starts.
title: ""
labels: []
---

**Priority:** P? · **Size:** S/M/L · **Area:** <!-- retrieval quality, correctness, docs, ... -->
<!-- Epic sub-issue? Name the epic and what this depends on: "Part of #32, after #33". -->

## Problem
<!-- What is wrong or missing today, with evidence: file:line links at a commit, a failing
query, a ragEval number. Someone who has not seen the code should understand why it matters. -->

## Proposed change
<!-- The approach, the classes and properties it touches, and any signature or API change.
New RAG stages go behind a property that defaults to off. Open design questions go here, each
with the options and what a spike must answer. -->

## Acceptance criteria
<!-- Checkable by a test or a command, not by opinion. "An IT on real Solr shows ...",
"./gradlew ragEval recall@20 for follow-ups >= baseline". Include error cases and edge inputs. -->
- [ ]

## Failure and rollback
<!-- Required. -->
- **When a step fails at runtime** (Solr error, model timeout, malformed model output, outage):
  <!-- expected behaviour: degrade to what, with which log line; never a 5xx for an optional stage -->
- **How the change is undone:** <!-- flag to turn off, revert, configset re-upload, re-index,
  data migration back; see docs/rollback.md. Say if any step is not reversible. -->

## Out of scope
<!-- Required: what this issue deliberately does not do, and where that work lives (#issue). -->
-
