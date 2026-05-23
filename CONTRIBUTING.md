# Contributing

This document defines shared contribution rules for GrapheneOS repositories. 
Repository-specific rules may add stricter requirements.

## Before Writing Code

Do not start implementing a new feature without first consulting with the
GrapheneOS team and discussing the proposal in the relevant GitHub issue.

The same applies to large bug fixes, behavior changes, migrations, rewrites,
dependency changes, SDK/target SDK changes, permission changes, storage changes,
or anything with compatibility, privacy, security, UX, or maintenance impact.

For this work, open or use an existing GitHub issue and make sure the expected
direction is clear before implementing. A pull request should be the result of
an agreed direction, not the first place where a feature or large bug fix is proposed.

Small, obvious, tightly scoped bug fixes may be submitted directly when the
problem and fix are clear. If there is any uncertainty about desired behavior,
compatibility, or project direction, discuss it first.

Pull requests ignoring this coordination requirement will be **closed without review**.

## AI-Assisted Contributions

AI tools may be used as assistants. They may not be used as a substitute for
understanding, design work, manual review, testing, or ownership.

Do not submit AI output you do not fully understand. This is prohibited.

Maintainers can easily identify pull requests that are fully AI-written. 
Do not assume this can be hidden.

Do not submit AI-assisted pull requests for parts of GrapheneOS you do not deeply understand. 
Deep understanding of the subject area is required before using AI to write code for it.

Before submitting AI-assisted work, you must manually review the complete diff
and be able to explain:

- what changed and why
- the important control flow, data flow, state changes, and error paths
- the security, privacy, permission, storage, lifecycle, and compatibility implications
- why each dependency, abstraction, API, and test was added or changed
- why the implementation is appropriate for this repository
- what alternatives were rejected and why

Reviewing generated code by asking another AI model is not manual review. 
A passing build is not proof that the code is correct. 
A generated PR description is not proof that the contributor understands the change.

## Unacceptable AI-Generated Submissions

Low-quality AI-generated submissions are not accepted. 
Pull requests will be closed without review when they show signs that the author did not understand,
review, or test the change.

AI patterns are easy to detect. 
If they appear in a pull request, maintainers will close it instead of spending review time proving 
that it was generated or explaining each problem.

Repeated low-quality AI-generated submissions may lead maintainers to stop reviewing further pull 
requests from the same contributor.

See [ai/llm-usage-guidelines.md](ai/llm-usage-guidelines.md) for the full AI
usage policy.

## Pull Request Expectations

Before opening a pull request:

- make sure the scope was discussed first when required
- keep the change focused and reviewable
- preserve existing user-visible behavior unless the change was agreed
- follow [guidelines/](guidelines/) for app architecture, code style, and
  testing guidance
- add or update tests for changed behavior
- cover happy paths, unhappy paths, edge cases, and regressions
- run the relevant build, static-analysis, unit-test, and coverage checks
- manually review the full diff
- write the pull request description in your own words

The pull request description should include:

- the linked issue or discussion
- what changed
- why the change is needed
- how it was tested, including exact commands when relevant
- compatibility, privacy, security, migration, or UX risks
- any checks that were not run and why
- any remaining limitations or follow-up work

Do not hide uncertainty. If part of the behavior is unclear, say so and ask for
guidance before expanding the implementation.
