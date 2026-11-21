# herd — an Android CLI launcher that runs like herdr

`herd` is an Android home-screen launcher built around a terminal. It is modelled on
[herdr](https://herdr.dev), the terminal workspace manager for coding agents. It brings herdr's
structure and habits to a phone:

- **It's a launcher.** Set it as your home app. `open spotify` (fuzzy) starts an app,
  `apps` lists them, and `search`, `url`, `call` and `settings wifi` do what they say.
- **Detach without stopping work.** A foreground service is the "server". It owns every shell,
  so closing the UI (`ctrl+b q`) leaves everything running, and reopening reattaches. After a
  reboot or process death the layout is restored. As in herdr, the processes themselves don't
  survive that.
- **Workspace → tab → pane.** These use herdr's model and its ids: `w1`, `w1:t1`, `w1:p1`. Panes
  split right or down, resize by dragging the divider, and zoom, swap and close.
- **You never hunt for the stuck one.** Every pane is `working`, `blocked`, `done`, `idle` or
  `unknown`. States roll up to tab and workspace badges, the sidebar, and the status counter. A
  pane that becomes blocked, or finishes while you're away, posts a notification that jumps
  straight to it.
- **Agent-native.** `herdr …` works in the input bar, and also from scripts running *inside*
  a pane. They can split panes, run commands in other panes, read their output and wait on them.
- **Keyboard and touch are both first-class.** The default prefix keys work on a hardware
  keyboard. A key row and a `⌃B` menu offer the same actions by touch.

## Keys (herdr defaults, prefix `ctrl+b`)

| | |
|---|---|
| `c` new tab · `v` split right · `-` split down | `h j k l` / arrows: focus · `H J K L`: swap |
| `z` zoom · `x` close pane · `r` resize mode | `[` copy mode |
| `n` / `p` next/prev tab · `1`–`9` tab | `T` rename tab · `X` close tab |
| `N` new workspace · `W` rename · `D` close | `w` workspace navigation · `g` go-to picker |
| `b` toggle sidebar · `q` detach | `?` all keys |

In the input bar, `tab` completes (commands, app names, herdr subcommands, pane ids), `↑`/`↓`
walk the history, and `ctrl+c` interrupts. Press `ctrl+c` twice to restart a stuck shell. While a
command is running, whatever you type goes to its stdin, which is how you answer a blocked pane.

## The `herdr` CLI

```
herdr status
herdr workspace list | create [--label T] [--cwd P] [--focus] | get | focus | rename | close <id>
herdr tab       list [--workspace W] | create [--workspace W] [--label T] [--focus] | get | focus | rename | close <id>
herdr pane      list | current | get <id> | close <id> | rename <id> <label>|--clear
herdr pane      split [<id>] --direction right|down [--ratio F] [--cwd P] [--focus]
herdr pane      focus <id> | focus --direction D | resize --direction D [--amount F] | swap --direction D | zoom [<id>]
herdr pane      read <id> [--lines N] | send-text <id> <text> | send-keys <id> <key>… | run <id> <command>
herdr agent     list | get | read | focus | rename <target> <name>
herdr agent     prompt <target> <text> [--wait] [--until STATUS]… [--timeout MS]
herdr agent     wait <target> [--until blocked|working|done|idle|unknown]… [--timeout MS]
herdr notification show <title> [--body TEXT]
herdr server stop
```

Typed commands print readable text, and `--json` gives herdr's `{"result": …}` shape. Calls from
scripts inside a pane get JSON by default, as they do with herdr. Each pane's shell has
`HERDR_PANE_ID`, `HERDR_TAB_ID` and `HERDR_WORKSPACE_ID` set, plus a `herdr` shell function.
Child scripts can call `sh "$HERDR_SHIM" …` instead. For example, from one pane:

```sh
p=$(herdr pane split --direction right | sed -n 's/.*"pane_id": "\(.*\)".*/\1/p' | head -1)
herdr pane run "$p" "ping -c 5 example.com"
herdr agent wait "$p" --until idle
herdr pane read "$p" --lines 3
```

## How it differs from herdr

- **No PTY.** Panes are long-lived `/system/bin/sh` processes with pipes, so line-oriented
  programs work well but full-screen TUIs (vim, top) don't. Colours (SGR) and `\r` progress
  lines are rendered, and other escape sequences are dropped.
- **Heuristic states.** herdr recognises agents by their screen. herd treats a pane as `working`
  while output flows and `blocked` when it goes quiet on something that looks like a prompt
  (`?`, `:`, `[y/n]`, `password`…). It is `unknown` after 15 s of silence. Known agent names
  (`claude`, `codex`, `gemini`, `opencode`, `aider`, …) are shown as the pane title.
- **One session, one client.** There are no named sessions, SSH machines, worktrees or plugins.
- **The API is loopback TCP with a per-boot random token**, not a Unix socket. Panes use toybox
  `nc` to reach it.

## Build

```sh
cd android-launcher
./gradlew assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

This needs JDK 17+ and an Android SDK with platform 34 (set `sdk.dir` in `local.properties` or
`ANDROID_HOME`). CI builds the APK on every push and uploads it as the `herd-launcher-debug`
artifact. Then press Home and choose **herd**, or run `settings home`.
