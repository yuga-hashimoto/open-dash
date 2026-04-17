---
name: sick-day
description: Gentle rest mode for when the user is unwell — dim lights, low volume, quiet hours, and a single hydration reminder every two hours without lecturing.
---
# Sick Day

Trigger on "I'm sick", "not feeling well", "feeling sick", "I have a
cold", "got the flu", "体調悪い", "風邪ひいた", "熱ある". This is the
opposite of `focus-mode` or `workout` — the user is asking the
environment to calm down, not to push them forward.

The tone matters: no cheerful brightness, no "you got this!" copy.
Short acknowledgement, then go quiet.

## Default flow

1. **Acknowledge once, softly**: "Got it. Rest up." /
   "お大事に。ゆっくりして。" — single sentence. No questions.
2. **Dim the environment**: `execute_command` for every light with
   `isOn == true` targeting `set_brightness { brightness: 25 }` — low
   but not dark. Keeps a safe navigation level for bathroom trips.
3. **Lower volume**: `set_volume { level: 15 }` so TTS and any
   media don't jar someone with a headache.
4. **Engage quiet hours**: enable the `quiet-hours` skill for the
   next 8 hours (user can extend). Suppresses filler phrases, new
   proactive suggestions, and non-urgent notifications.
5. **Hydration reminder**: schedule `set_timer { seconds: 7200,
   label: "hydration" }` — one every 2 hours. When it fires, the
   follow-up line is just "Sip some water." / "水、一口飲んで。" —
   not a dialog.
6. **Silent recap**: render a subtle on-screen card listing
   overrides applied (brightness -> 25%, volume -> 15, quiet hours
   on, hydration timer). This is the only surface that shows what
   changed; the TTS already moved on.

## What this skill does NOT do

- **No medical advice.** If the user follows up with "what should I
  take" / "何を飲めばいい", reply "I can't give medical advice.
  Check with a clinician." / "医療相談には応じられません。". No
  paraphrasing of home-remedy folklore.
- **No emergency detection.** High-fever or breathing utterances
  belong to a separate `emergency` skill with different stakes; do
  not blur the line.
- **No calendar clearing.** Cancelling real appointments is too
  destructive to auto-do. Offer, don't do: "Want me to read your
  calendar so you can cancel things?" only if the user asks.
- **No logging / tracking.** Illness is private. We do not store
  "user was sick on DATE" in memory.

## Follow-ups

- "Stop" / "解除して" within 4 hours → restore brightness, volume,
  and quiet-hours to the pre-skill snapshot (each step above should
  capture the prior value before overwriting).
- "Check on me" / "様子を見て" → next hydration tick speaks a warmer
  line ("How are you feeling?") once, then reverts to the minimal
  "Sip some water." default.
- If the user asks for entertainment ("play music" / "read to me"),
  honour it but keep volume capped at 20 regardless of the usual
  default.

## Accessibility note

This is the one skill where a screen reader's audible output should
also be softened. If TalkBack is active, set its speech rate to 0.9x
for the session — consistent with the rest of the dim-and-quiet
policy.
