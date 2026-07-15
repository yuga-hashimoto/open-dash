# Termux Bridge — opt-in shell access

**Status:** experimental, power-user. Off by default. Disabled on stock builds.

The Termux Bridge lets the assistant run shell commands on your tablet
through a locally-installed [Termux](https://termux.dev/) app — git, python,
ffmpeg, adb, anything in your Termux PATH. It fills the "I don't have a
first-class tool for this, but you already do this in Termux every day"
gap without baking each command into the app.

Inspired by [Mohd-Mursaleen/openclaw-android](https://github.com/Mohd-Mursaleen/openclaw-android)'s
shell-via-intent pattern, but fundamentally different from that repo's
3-layer Termux + Node + localhost HTTP stack — OpenDash stays
single-process native Kotlin and treats Termux as one opt-in tool, not a
runtime host.

## What the agent can do once enabled

```
"run git status in my project folder"
"what's in my Downloads directory?"
"compress that video with ffmpeg"
"tap the third button in the current screen"   ← via adb (see below)
```

## The three gates

The `termux_shell_exec` tool is invisible to the assistant unless all
three gates are open. Revoke any one and the tool disappears on the next
turn — the model cannot reference a capability it was never shown.

| Gate | Controlled by | Defaults to |
|---|---|---|
| Termux installed | `PackageManager.getPackageInfo("com.termux")` via the manifest `<queries>` entry | F-Droid install |
| `com.termux.permission.RUN_COMMAND` granted | Android runtime permission dialog | denied |
| `TERMUX_SHELL_EXECUTE_ENABLED` preference | **Settings → Advanced → Termux shell access** switch | **false** |

## Setup

### 1. Install Termux

Install Termux from [F-Droid](https://f-droid.org/en/packages/com.termux/).
**Do NOT use the abandoned Play Store version** — it's outdated and the
RUN_COMMAND intent doesn't work.

### 2. Grant RUN_COMMAND permission

Termux treats RUN_COMMAND as a "grant only to trusted apps" permission.
Add OpenDash to its allowlist:

```bash
# inside Termux
mkdir -p ~/.termux
cat >> ~/.termux/termux.properties <<EOF
allow-external-apps = true
EOF
termux-reload-settings
```

Then in Android Settings → Apps → OpenDash → Permissions, grant
*"Termux RUN_COMMAND"* (the exact label may vary by Android version).

### 3. Enable in OpenDash

**Settings → Advanced → Termux shell access** — toggle on. The card
shows live status lines:

- ✓/✗ Termux installed
- ✓/✗ RUN_COMMAND permission granted

If both are ✓ and the switch is on, the `termux_shell_exec` tool is now
advertised to the assistant.

### 4. (Strongly recommended) Set a command allowlist

By default, the assistant can invoke *any* binary in Termux's PATH —
including `rm`, `dd`, anything destructive. Narrow it:

**Settings → Advanced → Command allowlist** — comma-separated absolute
paths. Examples:

```
/data/data/com.termux/files/usr/bin/git
/data/data/com.termux/files/usr/bin/python
/data/data/com.termux/files/usr/bin/ls
```

When the allowlist is non-empty, any command not on it is rejected
**before** the bridge dispatches anything — no intent is sent, no
Termux process is started. Leaving the field empty means "no
restriction" (backward-compatible).

## Tool schema

```json
{
  "name": "termux_shell_exec",
  "parameters": {
    "command": "absolute binary path (required)",
    "arguments": ["optional", "string", "args"],
    "working_dir": "optional cwd",
    "timeout_ms": 30000
  }
}
```

Returns:

```json
{"exit_code": 0, "stdout": "...", "stderr": "..."}
```

Non-zero `exit_code` is surfaced as a tool-level failure (`error: exit=N`),
timeout as `error: timed out`, and an uninstalled/revoked Termux as
`error: disabled`.

## ADB self-control (advanced)

If you've set up `adb tcpip 5555` once from a computer ([ADB-BRIDGE.md
pattern](https://github.com/Mohd-Mursaleen/openclaw-android/blob/main/ADB-BRIDGE.md)),
you can run `adb connect localhost:5555` inside Termux and then have the
assistant drive the tablet via adb:

```
Add to allowlist: /data/data/com.termux/files/usr/bin/adb

Assistant can now run:
adb shell input tap 500 800
adb shell screencap -p /sdcard/screen.png
adb shell am start -n com.example/.MainActivity
```

This is an alternative to [Phase 15's
AccessibilityService](tablet-control-cookbook.md) tablet control —
AccessibilityService is the primary, first-class path; adb is a
fallback for apps where a11y doesn't cooperate, at the cost of more
setup.

## Security posture

The Termux Bridge deliberately does **not**:

- Ship an "always-on" default. All three gates default closed.
- Silently run commands. Even with the bridge enabled, the assistant
  must name the command in its `tool_call`, which is reflected in the
  current turn's conversation history.
- Execute anything when the preference is toggled off mid-session —
  `execute()` re-checks all gates on every call so a stale `tool_call`
  that races with a toggle cannot slip through.
- Expose secrets. The bridge inherits the user's Termux environment as
  is; if you export `GITHUB_TOKEN` in `.bashrc`, the assistant can see
  it when invoking `env` or `git config`. Use [`SecurePreferences`](privacy.md)
  for credentials OpenDash itself needs.

Still open (contributions welcome):

- **Spoken confirmation**: a hard pre-dispatch voice gate is not implemented.
  The current switch and command allowlist are policy gates; do not describe
  the bridge as requiring a spoken yes before execution.
- **Per-tool audit log**: Termux commands are logged via Timber/logcat
  today; a persistent, in-app audit surface for *"what did the
  assistant run last week?"* would help power users trust the gate.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Card shows ✗ Termux installed even though Termux is open | Ensure you installed from F-Droid (not Play); restart OpenDash so the PackageManager cache refreshes |
| Card shows ✗ RUN_COMMAND permission missing | Open Android Settings → Apps → OpenDash → Permissions → enable Termux RUN_COMMAND |
| Assistant says *"Command is not on the allowlist"* | Add the exact absolute path to the allowlist field, comma-separated |
| `exit=127` errors | Command not found. Use absolute paths — `/data/data/com.termux/files/usr/bin/git`, not just `git` |
| Bridge fails silently | Check logcat for `Termux RUN_COMMAND timed out` — either Termux is swipe-killed from recents, or a long-running command exceeded `timeout_ms` |

## Related

- [permissions.md](permissions.md) — how OpenDash tracks runtime permissions
- [tablet-control-cookbook.md](tablet-control-cookbook.md) — the a11y-based primary path for tablet control
- [openclaw-android](https://github.com/Mohd-Mursaleen/openclaw-android) — the inspiration repo
