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
