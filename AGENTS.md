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

- Follow [ADR-0001](docs/decisions/0001-story-world-multiworld.md), [ADR-0002](docs/decisions/0002-stack-and-world-policies.md), [ADR-0003](docs/decisions/0003-inventory-performance-and-recovery.md), [ADR-0004](docs/decisions/0004-community-quests-and-chapter-skip.md), [ADR-0005](docs/decisions/0005-dungeon-spawning-and-difficulty.md), and [ADR-0006](docs/decisions/0006-quests-dialogue-cutscenes-and-combat-revival.md). These are design approvals, not implementation validation or operational approval.
- Use Paper 26.2, Java 25, Gradle, PostgreSQL, a single plugin JAR with internal feature modules, Docker Compose, and existing Zabbix monitoring. Target vanilla Minecraft Java Edition clients, minimize separate client mods, and provide a server resource pack when needed. AI service technologies remain undecided.
- Run story progress worlds in one Paper process. Clone versioned, prebuilt spatial templates; add templates over time and preserve existing player worlds and progress. Save and unload inactive worlds, restoring them on reconnect.
- Keep NPC placement, dialogue, and quest definitions in separate content data. Store player progress, quests, NPC relationships, and story branches in PostgreSQL with per-world logical isolation. Separate story and economy inventories and progress.
- Protect story worlds by default; allow building and terrain changes only in designated areas. Protect designated cities and shared facilities in economy worlds while allowing more freedom elsewhere. Center major resource supply in both modes on dedicated gathering instances; treat building terrain changes and gathering as separate policies.
- Do not assume decisions on economy process separation, gathering instance creation/reset/regeneration, AI implementation, applying new terrain/buildings to existing worlds, or detailed content/game systems. Preserve common AI progress isolation and future A-option placement separation.

- Prioritize server performance and smooth play over complex automatic item recovery. Keep ordinary chests/warehouses in Minecraft world files and player inventories primarily in PostgreSQL. Prevent normal-play duplication/loss through basic concurrency and save ordering; do not require a DB transaction or synchronous disk flush for every item movement. Complex escrow storage, costly checkpoints, and fully automatic recovery are optional, not required. Add safeguards only when simple and low-cost; omit them if load or latency is high. Allow administrators to handle some unexpected-crash item loss/duplication using logs and backups; retain minimal error records and intervention procedures. Never promise automatic lossless recovery or duplication prevention in all circumstances.

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

## Community and Story Quest Policy

- Follow ADR-0004: co-op uses a Community sharing one story_run_id, world and major outcomes. Do not implement an in-game party leave system or carry forward party leader/split/merge proposals as requirements. Joining and administrator membership changes remain undecided.
- Keep personal inventory, quest completion and reward records separate by Community and player. Important Community quests require every currently online member to meet conditions; offline members are excluded. New logins must not interrupt the current combat/cutscene/quest stage; recheck all currently online members at the next important stage and block entry if any fail. Preserve completed stages. Exact stage boundaries and login/logout races remain undecided.
- Preserve fixed Community branches and world/NPC state during personal catch-up, assistance and per-chapter skips. Skip main and required roadside quests of the selected previously progressed chapter, satisfying personal next-chapter prerequisites; do not assume optional side quests are bulk skipped.
- Do not decide skip/helper rewards, joining procedure, stage-boundary/login races, vote abstention/time limits/roster changes, or past-scene instance/cutscene/NPC implementation without approval. Physical replacement schemas remain proposals.

- Important branch choices require satisfied progression conditions and a vote of currently online Community members: more than 50% of all eligible members. No majority or a tie means no commitment and a revote. One online eligible member may choose. Committed branches never change; late members follow them.
- Follow ADR-0005 for combat dungeons: inner-boundary entry triggers spawning; a larger boundary supplies the player count at spawn time. Scale monster count/health/attack from that count and keep spawned difficulty fixed; recount at the next zone and suppress unintended duplicate events. Sizes, multipliers, boss details, respawns/reset and creation/ownership/reentry remain undecided; death/spectating/conditional revival follow ADR-0006, with its implementation details unresolved. Do not infer gathering-instance lifecycle from combat spawning.
- Separate reward execution from editable quest/dungeon reward data; aim to change values without code edits/rebuilds. Defer amounts/multipliers/skip/helper balancing until story and gameplay are concrete. Storage format and reload/application details remain undecided; retain ADR-0003 reward deduplication and immediate payout-state persistence.

## Public Story Information Boundary

- This public repository contains general system design only. Never include actual story, character secrets, twists, endings, or novel manuscripts. Do not read or copy from hearth-story-private or import its content into this repository.

## Quest, Cutscene and Combat Policy

- Follow ADR-0006 for configurable quest starts, report-wait completion and authored failure checkpoints. Gameplay checkpoints do not require costly storage checkpoints or rollback shared outcomes/consumed items. Keep dialogue/choices/quest definitions separate from code; vanilla-client UI/camera technologies remain unverified.
- Only the triggering player watches a co-op cutscene. Ordinary dialogue preserves movement/view; important cutscenes may control them. First viewing is mandatory; disconnect does not complete it and reconnect restarts it. Ask to skip on the second challenge; choosing skip suppresses prompts on later retries, with an unspecified input for rewatching. Do not decide later behavior after declining that second skip. Track retries/view completion per player; never reapply shared outcomes or duplicate rewards. Chapter skipping remains separate.
- Normal bosses fail when all combat participants die; special battles may configure other failure conditions. Dead players spectate surviving allies with target switching and no combat intervention, returning before combat end only through configured revival conditions. Keep existing monster difficulty fixed.
- Configure revival conditions/disabled revival, personal or shared battle limits, health/effects and safe death/start/authored positions per battle. Preserve carried inventory/equipment without death drops or restoring consumed items. Keep ADR-0003 storage rules. Leave balance, participant/lifecycle details, failure/revival races, unsafe-location fallbacks and concrete implementation undecided.
