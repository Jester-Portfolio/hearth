# Repository publication safety

Hearth uses a local, zero-AI-token secret scanning gate.

## Before making the repository public

- Review current tracked files and Git history.
- Install Gitleaks on the Rocky Linux development host.
- Run `sh scripts/enable-git-security.sh` from the repository root.
- Verify `git config --local --get core.hooksPath` returns `.githooks`.
- Check `gitleaks git --redact --exit-code 1` passes.

## Protection

- `.gitignore` prevents common local secrets, credentials, runtime data, and world files from being added accidentally. It does not remove already tracked files.
- `.githooks/pre-commit` scans staged changes before commits.
- `.githooks/pre-push` scans reachable Git history before pushes.
- Hooks fail closed if Gitleaks is missing.
- Do not use `git -c core.hooksPath=/dev/null`, `--no-verify`, or `SKIP` to bypass security checks.
- Git hooks are local and are NOT automatically activated by cloning or pulling this repository.
- Keep real secrets outside the repository; use placeholder values in `.env.example`.
- If a real secret is committed or pushed, revoke/rotate it immediately; deleting the file is not enough.

## Limitations

Gitleaks uses detection rules and can miss unknown or unusual secrets. This is defense in depth, not a guarantee. GitHub secret scanning (when available) is an additional safety net, not a substitute for local hooks.
