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
- Include related documentation and code changes in the same commit by default, excluding unrelated files and unnecessary documentation edits. Commit and push only when instructed by the user or within an existing approved scope; a commit request does not imply permission to push.
- Never change existing Docker, Zabbix, or Cloudflare environments without approval. Documentation and roadmap entries do not authorize installation, service execution, or operational changes.
