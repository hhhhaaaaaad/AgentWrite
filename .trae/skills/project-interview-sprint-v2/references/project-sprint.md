# Project Sprint

Use this guide for project deep dives, codebase onboarding, implementation, debugging, or productionization.

## Minimal Fact Set

Read only:

1. current-state note if present;
2. README or architecture summary;
3. entry point and primary call path;
4. directly relevant modules and tests;
5. runtime evidence needed for the current question.

Expand the source set only to resolve a named uncertainty. Track:

| Claim | Label | Evidence | Gap |
|---|---|---|---|

Labels are defined in the main skill.

## Work Sequence

Use only the stages required by the chosen practice depth:

```text
baseline -> limitation -> decision -> implementation -> test
-> real evaluation -> failure analysis -> explanation
```

- `theory only`: architecture and tradeoffs; no edits.
- `inspect`: trace interfaces, data, state, and failures; no edits unless separately approved.
- `hands-on`: implement the smallest useful slice, run deterministic tests, then evaluate realistically when feasible.

Use fakes for deterministic logic and separate them from real models or services. State what each form of evidence proves and does not prove.

## Project Questions

Prioritize the project's actual:

- input/output chain and ownership boundaries;
- design alternatives and reasons;
- timeout, retry, fallback, partial, and refusal behavior;
- persistence, idempotency, security, observability, cost, and scale;
- metrics, denominators, sample size, environment, and failure cases.

Do not add a fixed topic ratio. Allocate time from the selected outcome, gaps, prerequisites, and evidence cost.

## Completion

Complete only the chosen scope. Carry missing work into the compact state note; do not generate a polished project pitch unless interview output was selected.
