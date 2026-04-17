---
name: remind-me-tomorrow
description: Capture "remind me tomorrow to X" into a dated memory entry the morning-briefing surface will read back automatically.
---
# Remind Me Tomorrow

Trigger on "remind me tomorrow", "remind me in the morning",
"明日", "明日思い出させて", "朝リマインド". Complements the broader
`task-manager` skill — that one handles generic "remind me to X"
with no time anchor; this skill specifically captures
**tomorrow-morning** intent into the shape `morning_briefing` already
knows to read.

## Default flow

1. **Extract the task**: everything after "remind me tomorrow to"
   or "明日…思い出させて". If the utterance is an adjective-only
   fragment ("the gas bill" / "ガス代"), wrap it as "remember to
   check <fragment>".
2. **Store with a tomorrow tag**: call
   `remember { text: "[tomorrow] <extracted task>", namespace: "reminders" }`.
   The `[tomorrow]` prefix is the contract `morning_briefing` scans
   for; leave it literal and bracket-surrounded.
3. **Confirm**: one sentence, never a question. Use the user's own
   words in the confirmation so they know we captured it verbatim.
   En: "Got it — I'll remind you tomorrow to X." JP: "了解。明日、
   Xをリマインドします。".

## Morning-surface contract

`morning_briefing` (both the tool and the
`MorningBriefingSuggestionRule`) look up
`search_memory { query: "[tomorrow]" }` and read back any hits found
at the top of the daily briefing. After reading, the flow clears each
read reminder via `forget` to prevent the item re-surfacing the
following day. The skill does NOT need to schedule anything — no
timers, no AlarmManager, no WorkManager. Calendar-like scheduling
belongs to `set_timer` / `set_alarm`.

## What this skill does NOT do

- **No multi-day horizons.** "remind me next Tuesday" is not this
  skill's job; that belongs to `set_alarm` or a future
  `schedule-reminder` skill.
- **No repeating reminders.** The entry is single-fire. Use
  `quiet-hours` / `hydration-reminder` for recurring nudges.
- **No urgency classification.** The user picked "tomorrow" — we
  don't second-guess that with "this sounds urgent, today?".

## Follow-ups

- If the user adds details immediately after confirmation
  ("…and tell Alex"), append to the stored entry rather than
  creating a second one. One reminder per utterance is the contract.
- If the user says "forget tomorrow's reminder" /
  "明日のリマインド消して", call
  `forget { query: "[tomorrow]", namespace: "reminders" }` and
  confirm "Cleared." / "消しました。".
