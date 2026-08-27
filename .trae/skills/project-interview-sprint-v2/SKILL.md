---
name: project-interview-sprint-v2
description: Build a time-boxed learning sprint for one technical topic, a codebase or project, or interview preparation. Use when the user wants to quickly learn, implement, debug, explain, or prepare questions around a technology or project. Assess the user's theory, practice, and interview-expression levels; offer three time options; require confirmation before planning; then teach adaptively with minimal context, optional hands-on work, evidence tracking, and deduplicated Obsidian notes.
---

# Project Interview Sprint V2

Create the smallest learning process that reaches the user's chosen outcome. Do not start a full sprint, inspect a large repository, modify code, or generate heavy artifacts before configuration is confirmed.

## 1. Configure Before Teaching

Collect missing fields in one compact form. Do not ask again for facts already supplied.

Treat configuration as a hard gate:

- On the first response, request every missing configuration field and do nothing else.
- Before the three self-ratings, goals, practice depth, and answer detail are known, do not diagnose, teach, browse, inspect files, recommend durations, or create a plan.
- After configuration is complete, recommend the three time options but do not begin the lesson.
- Begin only after the user selects an option and confirms the summarized configuration.
- A request such as "start teaching me" does not bypass missing configuration.

```text
Topic or project:
Goals (multi-select): understand theory / read implementation / implement / interview / productionize / debug
Self-rating (1-5): theory / practice / interview expression
Practice depth: theory only / inspect existing implementation / hands-on implementation
Answer detail: concise / standard / deep
Available time or deadline (optional):
Source files and Obsidian path (optional):
```

Explain that ratings measure:

- `1`: nearly unfamiliar;
- `3`: understands basics but cannot apply or explain reliably;
- `5`: can implement, handle tradeoffs and failures, and explain with evidence.

Estimate knowledge volume from the target, selected goals, ratings, and supplied materials. Offer:

1. **Minimum pass**: core chain and common questions.
2. **Recommended mastery**: mechanisms, tradeoffs, transfer questions, and selected practice.
3. **Deep practice**: implementation, tests, experiments, production boundaries, and interview evidence when relevant.

For each option state duration, scope, expected outcome, and exclusions. Let the user select an option. If the user gives a hard time limit, fit all three options within it or explain why the deepest outcome does not fit.

After selection, summarize the configuration and ask for confirmation. Start only after confirmation.

## 2. Route Only What Is Needed

Read exactly one primary guide after confirmation:

- Single concept or technology: [references/topic-sprint.md](references/topic-sprint.md)
- Project, codebase, or productionization: [references/project-sprint.md](references/project-sprint.md)
- Job description, mock interview, or interview artifacts: [references/interview-sprint.md](references/interview-sprint.md)

Read [references/recording.md](references/recording.md) only when notes, Obsidian, or cross-session continuation is requested. Combine guides only when the confirmed goals require it.

## 3. Minimize Context And Token Use

- Start from user-provided facts and the smallest relevant source set.
- For a project, begin with README, entry point, core path, relevant tests, and current-state note. Expand only to resolve a concrete gap.
- Prefer `rg` and targeted reads. Do not scan all code or reload all historical notes by default.
- Use local sources first. Browse only for unstable facts, missing evidence, official documentation, or explicit requests.
- Before broad repository reading, many-document synthesis, or a heavy artifact, state the scope and ask for confirmation.
- Do not repeat the plan, known facts, or full standard answers on every turn.
- Generate interview scripts, complete knowledge bases, requirement tables, and coverage audits only when explicitly selected.

## 4. Teach Adaptively

Ask one core question per turn. Combine two or three short questions only when they test the same concept.

Scale feedback:

- **Correct**: confirm briefly and add at most one important omission.
- **Partly correct**: repeat the question, preserve the correct part, fix only the gaps, and provide one concise restatement.
- **Incorrect or unknown**: give the minimum complete explanation, one standard answer, then ask a nearby transfer question.

Increase depth only as needed:

```text
definition -> mechanism -> tradeoff -> failure -> application -> evidence -> production boundary
```

Large schedule changes require user confirmation. Small reallocations may be made automatically with a one-sentence reason.

Stop a topic when the learner can:

1. explain it accurately in their own words;
2. solve one transfer or scenario question;
3. name one common failure or limitation;
4. pass the selected implementation or experiment check when practice is in scope.

Do not continue drilling a mastered topic.

## 5. Keep Claims Honest

For project claims use:

- `implemented`
- `test-verified`
- `experiment-observed`
- `design-only`
- `not-implemented`

Prefer current code, deterministic tests, and runtime evidence over old notes. Keep tests separate from real evaluation and retain meaningful failure cases. Never turn reading or understanding into claimed implementation or production experience.

## 6. Finish Compactly

At a checkpoint, report only:

- mastered items;
- remaining weaknesses;
- evidence produced;
- next action;
- any changed rating.

When recording is enabled, update the compact current-state note and only changed knowledge cards. Do not duplicate the same answer across daily reviews, cards, and scripts.
