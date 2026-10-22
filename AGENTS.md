# Hearth Project — Codex Instructions

## Project
Hearth is a Minecraft-based RPG and server engineering project.

The project includes:
- A single-player/co-op story world.
- A separate economy-focused multiplayer world.
- Realistic medieval technology without magic.
- NPCs, quests, world progression, and economic systems.
- A local AI assistant for the story world.
- Docker-based deployment and Zabbix monitoring.

## Confirmed Stack and World Policies

- Follow [ADR-0001](docs/decisions/0001-story-world-multiworld.md) and [ADR-0002](docs/decisions/0002-stack-and-world-policies.md). These are design approvals, not implementation validation or operational approval.
- Use Paper 26.2, Java 25, Gradle, PostgreSQL, a single plugin JAR with internal feature modules, Docker Compose, and existing Zabbix monitoring. Target vanilla Minecraft Java Edition clients, minimize separate client mods, and provide a server resource pack when needed. AI service technologies remain undecided.
- Run story progress worlds in one Paper process. Clone versioned, prebuilt spatial templates; add templates over time and preserve existing player worlds and progress. Save and unload inactive worlds, restoring them on reconnect.
- Keep NPC placement, dialogue, and quest definitions in separate content data. Store player progress, quests, NPC relationships, and story branches in PostgreSQL with per-world logical isolation. Separate story and economy inventories and progress.
- Protect story worlds by default; allow building and terrain changes only in designated areas. Protect designated cities and shared facilities in economy worlds while allowing more freedom elsewhere. Center major resource supply in both modes on dedicated gathering instances; treat building terrain changes and gathering as separate policies.
- Do not assume decisions on economy process separation, gathering instance creation/reset/regeneration, AI implementation, applying new terrain/buildings to existing worlds, or detailed content/game systems. Preserve common AI progress isolation and future A-option placement separation.

## Development
- Prefer simple, maintainable solutions.
- Explain major architecture decisions before implementation.
- Keep story and economy world data logically separate.
- Preserve existing project files and player data.
- Use Git to track source code changes.
- Run relevant tests after modifications.
- Report what changed and what was verified.

## Server Environment
- OS: Rocky Linux 9.8.
- Host: rocky-laptop.
- Development user: server.
- Project root: /home/server/projects/hearth.
- Existing services include Docker, Zabbix, and Cloudflare Tunnel.

## Operational Safety
- Never assume root privileges are available.
- Do not modify unrelated services or system configuration.
- Inspect existing configuration before proposing changes.
- Require explicit user approval before:
  - Restarting or stopping production services.
  - Modifying firewall, SSH, or Cloudflare configuration.
  - Changing Docker infrastructure outside Hearth.
  - Installing or removing system packages.
  - Deleting persistent data or performing destructive operations.
- Prepare a rollback plan for significant operational changes.
- Never expose credentials, tokens, or private keys.
- Never commit secrets to Git.

## Workflow
- Development and safe tests may proceed autonomously.
- For significant infrastructure changes, present a plan first.
- Ask for approval before applying operational changes.
- Distinguish verified facts from assumptions.
- Do not claim success without checking the result.

## Official Project Records

- Git repository documents are the official shared project record for ChatGPT and Codex. ChatGPT handles planning, architecture, and result review; Codex handles implementation, testing, and infrastructure work within the approved scope. Do not assume automatic access to the other environment's conversations or local changes; share relevant documents and commit references when handing off work.
- At task start, read [PROJECT_STATUS](docs/PROJECT_STATUS.md), [ROADMAP](docs/ROADMAP.md), and related [ADRs](docs/decisions/README.md), along with relevant design and code.
- At task completion, update PROJECT_STATUS and ROADMAP when progress or next steps changed, and record major changes in [CHANGELOG](docs/CHANGELOG.md). Include confirmation dates, evidence, test scope, and related commit references where applicable. Do not edit documents unnecessarily when their content remains accurate.
- Keep responsibilities separate: PROJECT_STATUS summarizes current facts, ROADMAP owns stage order and completion criteria, CHANGELOG records major changes, and ADRs own confirmed architecture decisions. Link to detailed records instead of duplicating them.
- Manage confirmed design changes through ADRs. Preserve comparison and feasibility documents, and mark adopted or superseded decisions clearly. Architecture approval is not implementation validation or operational approval.
- Never mark unfinished work complete or untested functionality verified. Distinguish design, implementation, execution tests, and deployment; report partial checks and unverified items explicitly.
- If documentation and actual code conflict, inspect the evidence and report the discrepancy before deciding which record to update. Do not silently treat a proposal as implemented behavior.
- Include related documentation and code changes in the same commit by default, excluding unrelated files and unnecessary documentation edits. Follow the Git Work Policy below for user-approved development and documentation work.
- Never change existing Docker, Zabbix, or Cloudflare environments without approval. Documentation and roadmap entries do not authorize installation, service execution, or operational changes.

## Git Work Policy

- User approval of development or documentation work authorizes automatically committing and pushing the completed work without a separate confirmation. Explicit task-specific instructions to defer or omit commits or pushes take precedence.
- At task start, inspect the branch, working tree, and staging area, and record pre-existing uncommitted changes. Do not include pre-existing changes or files from other tasks without explicit authorization. If changes overlap and cannot be safely separated, stop and report.
- Before committing, complete relevant tests and verify the final changes. Update PROJECT_STATUS and ROADMAP when facts, progress, or next steps changed, and record major changes in CHANGELOG. Do not claim untested functionality is verified.
- Stage only files or hunks belonging to the approved task. Inspect the staged diff and create commits in meaningful units with clear messages.
- Fetch origin and check the current branch and its relationship to origin/main before committing and again before pushing. Automatically push verified task commits to origin/main only when the branch is main and its history extends the latest origin/main without unrelated unpublished commits. If these conditions are not met, stop and report instead of switching branches, merging, or rebasing automatically.
- If tests fail, conflicts occur, the remote branch does not match the expected history, or a push fails, stop the commit/push workflow and report the evidence and remaining work. Do not bypass failed checks or force a push.
- Force pushes, reset --hard, rewriting existing commit history (including amend or rebase), and branch deletion always require separate explicit approval.
- Git commit/push authorization does not authorize operational changes. Changes to existing Docker, Zabbix, or Cloudflare environments still require separate explicit approval.
- At completion, briefly report commit hashes, push results, and test results, including partial checks or anything unverified.
