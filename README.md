<img src="docs/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# house-elves

Installs [Elf Desk](https://github.com/House-elves/elf-desk) and its [runner](https://github.com/House-elves/elf-runner) on your laptop, Linux or macOS, and keeps them running. One desk per person: it works as your own GitHub account, and nothing of yours leaves your machine.

## Install

You need [jbang](https://www.jbang.dev) (it brings Java itself), plus:

- **git**, and **gh** logged in to the GitHub account the desk should work as (`gh auth login`).
- **Claude Code** (`claude`), with a token for the runner: `claude setup-token` prints one, and the installer asks for it. It runs on your Claude subscription, not API billing.
- **Linux:** bubblewrap (`dnf install bubblewrap` or `apt install bubblewrap`), the agent's sandbox. **macOS:** nothing more (it uses the built-in `sandbox-exec`).
- **Optional:** Podman or Docker, for agent reviews whose tests use Dev Services or Testcontainers.

Then:

```
curl -Ls https://sh.jbang.dev | bash -s - app install house-elves@House-elves
house-elves install
```

`install` checks all of that first and says exactly what's missing. It then:
1. asks which `gh` account the desk works as, and who the agent's commits are by;
2. downloads the latest desk and runner;
3. installs them as services (systemd user units on Linux, launch agents on macOS) and opens the desk.

Run `house-elves install` again to update.

## Commands

| | |
|---|---|
| `house-elves open` | Opens the desk in your browser, signed in. `--print` prints the one-time link instead. |
| `house-elves doctor` | Checks the tools, logins, services and the desk, and says how to fix what isn't right. |
| `house-elves status` | Is the desk running and answering? |
| `house-elves start`, `stop`, `restart` | The desk and the runner together. |
| `house-elves logs desk`, `logs runner` | Their latest log lines. |
| `house-elves uninstall` | Removes the services and programs. Keeps your data and settings unless `--all`. |

## How it keeps the desk yours

- The desk listens on `127.0.0.1` only, and only answers requests addressed to `localhost` (so a web page can't reach it by pointing its own domain at your machine).
- You get in with a key only you can read (`~/.local/share/house-elves/desk.key`). `house-elves open` gives your browser a one-time link that sets it as a cookie.
- GitHub calls use `gh auth token` for the account you picked. Every write to GitHub (a review, a comment, a push, a merge) still waits for your click.
- The agent runs in a sandbox with no GitHub credentials: it can't read your home, `gh`'s login or (on macOS) the Keychain.
- To use the desk from another machine, forward the port over SSH: `ssh -L 7777:localhost:7777 <laptop>`, then run `house-elves open --print` on the laptop.

## Where things are

- `~/.config/house-elves/`: `desk.properties` and `runner.config` (your account, and the tokens: readable only by you).
- `~/.local/share/house-elves/`: the desk's database, its key, the programs (`app/`) and the runner's clones (`runner/`).
- Linux: `~/.config/systemd/user/house-elves-*.service`. macOS: `~/Library/LaunchAgents/io.github.house-elves.*.plist`, with logs in `~/Library/Logs/house-elves/`.
