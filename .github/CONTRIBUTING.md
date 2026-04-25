# Contributing to Orekas

Thanks for considering a contribution. This guide covers what's expected for code, commits, and pull requests.

## Development setup

Requirements:

- JDK 21 (Temurin recommended).
- Git.
- An IDE that understands Gradle + Fabric Loom (IntelliJ IDEA is the path of least resistance).

Clone and build:

```bash
git clone https://github.com/furkankoykiran/Orekas.git
cd Orekas
./gradlew build
```

Launch a dev client to test in-game:

```bash
./gradlew runClient
```

The first run downloads Minecraft assets and the Meteor Client jar; subsequent runs are fast.

## Branching

- Branch off `master`.
- Use a descriptive branch name like `feat/<thing>`, `fix/<thing>`, or `docs/<thing>`.
- Keep one branch per change set; rebase on `master` before opening the PR if `master` has moved.

## Commit messages

Use Conventional Commits (`feat`, `fix`, `chore`, `docs`, `refactor`, `test`, `ci`) so the changelog stays scannable. Example:

```
feat(orefinder): expand on chunk load to remove the per-tick budget bottleneck
```

Describe *why* the change matters, not just what was edited. One change per commit when practical.

## Pull requests

- Run `./gradlew build` locally before opening the PR.
- Verify the change in-game with `./gradlew runClient` for any user-visible behaviour.
- Fill out the PR template — especially the "Tested locally" checkboxes.
- Keep PRs focused. Drive-by refactors belong in their own PR.
- CI may not run when the repo's monthly Actions quota is exhausted; that's not a reviewer-side problem, but please don't rely on green CI as a substitute for local verification.

## Code style

- Match the surrounding style. The project uses standard Java conventions and four-space indentation.
- Avoid speculative abstractions. If something can be expressed in a few lines clearly, prefer that over a helper method or a new class.
- Add a comment only when *why* is non-obvious. Don't restate what the code already shows.

## Reporting issues

Use the bug report or feature request template under [Issues](https://github.com/furkankoykiran/Orekas/issues/new/choose). Provide enough detail to reproduce — Minecraft version, Meteor version, Orekas version, steps, and a relevant log excerpt.
