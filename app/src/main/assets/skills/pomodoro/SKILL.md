---
name: pomodoro
description: Classic 25/5 pomodoro cycle — strict 25-minute work blocks, 5-minute rests, and a longer 15-minute rest every fourth cycle.
---
# Pomodoro

Trigger on "pomodoro", "start pomodoro", "25 minute timer",
"pomodoro mode", "ポモドーロ". Distinct from `focus-mode` (an
open-ended deep-work session with lights and volume tweaks) and
`stretch-break` (a 5-minute standing micro-routine): pomodoro is a
**strict cadence** of 25-minute work blocks and 5-minute sit-rests,
with a longer 15-minute rest after every fourth work block. No
stretching, no lights, no media changes — just timers and cues.

## Default flow on activation

1. **One-sentence confirmation** at the user's current volume — do not
   bump volume on activation. Say "Pomodoro on. First 25 minutes
   starting now." (JA: 「ポモドーロ開始。まず25分です。」)
2. **Start the work timer** via `set_timer` with
   `{ seconds: 1500, label: "pomodoro-work" }`. The label scopes the
   session so we can cancel it without touching cooking / laundry
   timers.
3. **Track the cycle count** internally. The first work block is
   cycle 1. After the fourth work block ends, the following break is
   a long 15-minute break; the cycle counter then resets to 1.
4. **Do not broadcast.** Pomodoro is an individual focus practice —
   other speakers should stay silent. Explicitly avoid
   `broadcast_tts`, `broadcast_timer`, `broadcast_announcement`.

## When the work timer fires

1. **Check quiet-hours first.** If `quiet-hours` is active, suppress
   the voice cue — do not speak, do not bump volume. Still advance
   the cycle and schedule the break timer silently; the user opted
   into quiet hours, and pomodoro cues must not wake the room.
2. **One calm cue**, at the user's current volume:
   - EN: "Work block done. Five-minute break." (or on cycle 4:
     "Fourth block done. Take fifteen.")
   - JA: 「作業終了。5分休みましょう。」 (cycle 4:
     「4セット目終了。15分休みましょう。」)
3. **Schedule the break** via `set_timer`:
   - Cycles 1–3: `{ seconds: 300, label: "pomodoro-break" }`.
   - Cycle 4: `{ seconds: 900, label: "pomodoro-long-break" }`.

## When the break timer fires

1. **Check quiet-hours.** If active, suppress the voice cue and
   schedule the next work timer silently.
2. **One calm cue**, at the user's current volume:
   - EN: "Break over. Back to work — 25 minutes."
   - JA: 「休憩終了。次の25分です。」
3. **Schedule the next work block** via `set_timer` with
   `{ seconds: 1500, label: "pomodoro-work" }`. On the cycle after a
   long break, reset the cycle counter to 1.

## While active

- Sit-rest only — pomodoro breaks are for resting the head, not
  moving. Do not suggest stretches; that's `stretch-break`'s job.
- Do not narrate the remaining time mid-block. The user is working;
  silence is the feature.
- If the user asks "how many left?" / 「あと何セット？」, answer in
  one short sentence with the current cycle number.
- If the user says "skip break" / 「休憩スキップ」, cancel the pending
  break timer and start the next work block immediately.

## End-of-session

Trigger on "stop pomodoro", "pomodoro off", "ポモドーロ終了",
"もう終わり":

1. `cancel_all_timers` scoped to the pomodoro labels
   (`pomodoro-work`, `pomodoro-break`, `pomodoro-long-break`) only.
   Do NOT cancel unrelated timers — cooking and laundry timers must
   survive.
2. One short sign-off: "Pomodoro done." (JA: 「ポモドーロ終了。」)

## Interaction with quiet-hours

If the user enables `quiet-hours` mid-session, **do not auto-cancel**
the pomodoro. The per-fire check above handles the suppression. When
quiet-hours ends, the next scheduled cue fires normally — no catch-up
for missed cues.

## Multi-room

Do NOT broadcast. Pomodoro is individual. If the user has multiple
tablets running, each one tracks its own session.

## Volume

Use `set_volume` only if the user explicitly asks for a quieter or
louder cue ("pomodoro quieter", 「ポモドーロ音量下げて」). Never
auto-adjust volume on activation or between cycles.

## Style

- Tone: calm focus-keeper. No cheerleading, no exclamation marks, no
  "you've got this" phrasing.
- Keep each cue ≤ 8 words in EN, ≤ 20 characters in JA.
- JA register: polite-casual ("〜しましょう" is fine).
- Never use emoji in TTS text.

## Tools used
- `set_timer`
- `cancel_all_timers`
- `set_volume` (only on explicit user request)

## Tools explicitly avoided
- `broadcast_tts`, `broadcast_timer`, `broadcast_announcement` —
  pomodoro is personal, no fan-out
