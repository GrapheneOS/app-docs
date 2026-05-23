# AI And Agents

[AGENTS.md](AGENTS.md) is the single recommended agent instruction file for app
repositories. It is not intended to control this docs repository itself.

## Pages

- [AGENTS.md](AGENTS.md)
- [llm-usage-guidelines.md](llm-usage-guidelines.md)

## Adopting In An App Repository

Copy [AGENTS.md](AGENTS.md) into the target app repository root.

Then edit the copied [AGENTS.md](AGENTS.md) for the app:

- document the app's actual module names, source roots, build variants, and
  validation commands
- remove guidance that does not apply to the app
- add app-specific invariants, compatibility constraints, and legacy migration notes
- point the agent to the copied or checked-out docs directory, especially
  [guidelines/](../guidelines/), [references/](../references/), and any local
  plans

Copy useful [skills](skills/) for agents that support local skills.

Alternatively, copy them to the user's global skills directory if that is how
the agent environment is configured.

Gemini and Claude do not use [AGENTS.md](AGENTS.md) as their project
instruction file.
When using them, create symlinks from their expected files to the same
instructions so there is one source of truth:

```sh
ln -s AGENTS.md GEMINI.md
ln -s AGENTS.md CLAUDE.md
```
