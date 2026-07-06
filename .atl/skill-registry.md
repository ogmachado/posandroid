# Skill Registry — idos-pos-android

Detected: 2026-07-06
Scope: user-level only (project has no code yet; no project-level skills or convention files exist besides `.git`).

This registry is an index of exact `SKILL.md` paths. Sub-agents read the full skill source of truth at the listed path — this table is not a substitute for reading the file.

## SDD Phase Skills (excluded from general index per scan rules — listed for completeness)

| Skill | Path |
|---|---|
| sdd-init | `C:\Users\ogmac\.claude\skills\sdd-init\SKILL.md` |
| sdd-explore | `C:\Users\ogmac\.claude\skills\sdd-explore\SKILL.md` |
| sdd-propose | `C:\Users\ogmac\.claude\skills\sdd-propose\SKILL.md` |
| sdd-spec | `C:\Users\ogmac\.claude\skills\sdd-spec\SKILL.md` |
| sdd-design | `C:\Users\ogmac\.claude\skills\sdd-design\SKILL.md` |
| sdd-tasks | `C:\Users\ogmac\.claude\skills\sdd-tasks\SKILL.md` |
| sdd-apply | `C:\Users\ogmac\.claude\skills\sdd-apply\SKILL.md` |
| sdd-verify | `C:\Users\ogmac\.claude\skills\sdd-verify\SKILL.md` |
| sdd-archive | `C:\Users\ogmac\.claude\skills\sdd-archive\SKILL.md` |
| sdd-onboard | `C:\Users\ogmac\.claude\skills\sdd-onboard\SKILL.md` |

## General-Purpose Skills Index

| Skill | Trigger | Scope | Path |
|---|---|---|---|
| branch-pr | creating, opening, or preparing PRs for review | user | `C:\Users\ogmac\.claude\skills\branch-pr\SKILL.md` |
| chained-pr | PRs over 400 lines, stacked PRs, review slices | user | `C:\Users\ogmac\.claude\skills\chained-pr\SKILL.md` |
| cognitive-doc-design | writing guides, READMEs, RFCs, onboarding, architecture, or review-facing docs | user | `C:\Users\ogmac\.claude\skills\cognitive-doc-design\SKILL.md` |
| comment-writer | PR feedback, issue replies, reviews, Slack messages, or GitHub comments | user | `C:\Users\ogmac\.claude\skills\comment-writer\SKILL.md` |
| go-testing | Go tests, go test coverage, Bubbletea teatest, golden files | user | `C:\Users\ogmac\.claude\skills\go-testing\SKILL.md` (not applicable — this project is Kotlin/Android, no Go code) |
| issue-creation | creating GitHub issues, bug reports, or feature requests | user | `C:\Users\ogmac\.claude\skills\issue-creation\SKILL.md` |
| judgment-day | judgment day, dual review, adversarial review, juzgar | user | `C:\Users\ogmac\.claude\skills\judgment-day\SKILL.md` |
| skill-creator | new skills, agent instructions, documenting AI usage patterns | user | `C:\Users\ogmac\.claude\skills\skill-creator\SKILL.md` |
| skill-improver | improve skills, audit skills, refactor skills, skill quality | user | `C:\Users\ogmac\.claude\skills\skill-improver\SKILL.md` |
| work-unit-commits | implementation, commit splitting, chained PRs, or keeping tests and docs with code | user | `C:\Users\ogmac\.claude\skills\work-unit-commits\SKILL.md` |

## Notes

- Duplicate skill sets found at `~/.config/opencode/skills/` and `~/.copilot/skills/` — identical names to `~/.claude/skills/`, deduplicated; `~/.claude/skills/` paths used as canonical.
- No project-level skill directories exist yet (`skills/`, `.claude/skills/`, `.agent/skills/`, etc.) — project directory contains only `.git`.
- No project convention files exist yet (`AGENTS.md`, `CLAUDE.md`, `.cursorrules`, `GEMINI.md`, `copilot-instructions.md`).
- No Kotlin/Android-specific skill was found in any scanned skill directory (no `android-testing`, `kotlin`, `compose`, etc.). Flag for `sdd-explore`/`sdd-propose`/`sdd-design` phases: if Android-specific conventions emerge (e.g. Compose UI patterns, Room migration conventions), consider authoring a project-level skill via `skill-creator`.
- Re-scan this registry once the project has real files (build.gradle.kts, source tree) — stack-specific skills may become relevant then.
