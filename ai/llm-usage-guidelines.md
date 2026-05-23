# LLM Usage Guidelines

LLMs and coding agents may be used for GrapheneOS development, but only as assistive tools. 

The person submitting a change is responsible for every line of that change.
Using AI does not reduce that responsibility.

## Core Policy

Submitting AI-written code that you do not fully understand is prohibited.

Before submitting any AI-assisted change, you must be able to explain:

- what changed and why
- how the code fits the surrounding architecture
- the important control flow, data flow, state changes, and error paths
- the security, privacy, permission, storage, lifecycle, and compatibility
  implications
- why each dependency, API, abstraction, and test was added or changed
- why the implementation is appropriate for this repository
- what alternatives were rejected and why

If you cannot explain the change at that level, do not submit it. 
Rework it until you understand it, or do not use it.

## Required Manual Review

AI output must be treated as untrusted draft material. Before submitting it,
manually review the full diff line by line.

Manual review means reading and understanding the generated code yourself. It is
not enough to ask another LLM to review it.

Check at least:

- correctness against the local code and requested behavior
- consistency with [AGENTS.md](AGENTS.md), [guidelines/](../guidelines/), and
  local app documentation
- lifecycle, coroutine, Flow, Compose state, and cancellation behavior
- permission, role, intent, storage, notification, and exported-component
  behavior
- private data handling and logging
- dependency and build configuration changes
- tests for happy paths, unhappy paths, edge cases, and regressions
- whether the implementation is simpler than the alternatives

Compiling and passing tests are required validation, but they are not a
substitute for understanding and reviewing the code.

## Unacceptable Output

Do not submit low-quality AI-generated output. Pull requests will be closed
without review when generated output is not understood, reviewed, scoped, or
tested by the author.

Common signs of unacceptable generated output:

- broad rewrites or refactors not required by the task
- generic abstractions that do not match the existing architecture
- invented APIs, dependencies, Gradle configuration, tests, or behavior
- code that conflicts with the repository's [AGENTS.md](AGENTS.md),
  architecture, style, or testing rules
- PR descriptions or comments that do not match the actual diff
- vague explanations that could apply to any project
- missing tests for changed behavior
- build, lint, static-analysis, or test failures that would have been caught by
  running the documented checks
- unrelated formatting, cleanup, renaming, or churn
- changes that weaken privacy, security, permission, storage, or lifecycle
  behavior
- review replies that blindly apply new generated patches without understanding
  the review issue

Maintainers will close these pull requests instead of spending review time
debugging or explaining generated code.

## Acceptable Uses

AI can be useful for:

- summarizing unfamiliar local code after the relevant files are provided or
  inspected
- suggesting implementation approaches for a human to evaluate
- drafting small, reviewable code changes
- drafting tests and test-case matrices
- identifying edge cases and likely regressions
- explaining Android, Kotlin, Compose, Gradle, or library concepts after
  checking authoritative documentation
- reviewing a diff as an additional check after the author has reviewed it
- drafting documentation that a human then verifies and edits

Prefer small prompts and small generated diffs. Large AI-generated rewrites are
hard to review and should be avoided unless the change is intentionally a large
migration with a clear plan and prior agreement.

## Prohibited Uses

Do not use AI to:

- submit code you do not understand
- replace local code inspection
- replace manual review
- invent project policy, security policy, privacy behavior, or product behavior
- make security-sensitive decisions without maintainer review
- approve dependency, SDK, target SDK, permission, or storage-behavior changes
- make broad rewrites that are not required for the task
- hide uncertainty about generated code
- resolve review feedback by blindly applying generated patches
- handle private user data, private logs, screenshots, files, keys, tokens, or
  credentials

AI-assisted review is only an additional signal. It does not count as maintainer
review, author review, or security review.

## Author Responsibilities

When using AI, the author must:

- inspect the relevant local code before asking for or accepting changes
- keep the task and generated diff small enough to review thoroughly
- verify every claim the AI makes about the codebase
- prefer official Android, Kotlin, Jetpack, Gradle, and library documentation
  for API behavior
- check generated code against repository-local patterns and constraints
- run the relevant build, static-analysis, unit-test, and coverage checks
- manually test user-visible behavior when appropriate
- remove unused abstractions, generic boilerplate, and speculative code
- disclose uncertainty in the PR instead of letting generated code imply false
  confidence

The final PR description should reflect the author's understanding of the
change, not an unverified AI summary.

## Review Feedback

When maintainers ask questions about AI-assisted code, answer from your own
understanding and reference the local code. Do not paste model output as a
substitute for an explanation.

If review reveals that you do not understand the generated change, close or
rewrite the pull request. Do not keep iterating by blindly asking AI for patches.

## Good Workflow

1. Inspect the local code and docs yourself.
2. Ask AI for a narrow explanation, plan, test matrix, or small patch.
3. Read the generated output line by line.
4. Rewrite anything you do not understand or cannot justify.
5. Compare the result against the architecture, code-style, and testing docs.
6. Run the relevant checks.
7. Manually review the final diff before submission.

If a generated change surprises you, stop and understand it before continuing.
