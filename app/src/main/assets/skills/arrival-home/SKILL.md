---
name: arrival-home
description: Warm welcome when the user gets home — lights up the entry, says hi, and recaps the next calendar event plus any urgent notifications in one short line.
---
# Arrival Home

Trigger on "I'm home", "ただいま", "just got back", "just got home",
"家に着いた", "着いた". Distinct from `home-control` (generic lights /
devices), `morning-routine` (time-of-day), and `travel-mode` (leaving
the house): this is a short *return greeting* scoped to the 30 seconds
after the user walks in.

Default flow is **light-up + one-line briefing** — never a long
monologue. Someone who just dropped bags at the door does not want
to listen to a weather report.

## Default flow

1. **Lights up at entry**: issue `execute_command` for any device with
   name containing "entry", "genkan", "hallway", or "foyer" (fall back
   to the first light in the Light device list) to `turn_on` at
   brightness 60 — inviting, not harsh.
2. **Say hello once**: speak a single sentence matching the greeting
   register. En: "Welcome home." JP: "おかえりなさい". No follow-up
   question on this line.
3. **One-line recap**: in a second sentence, glue together
   - `get_calendar_events { hours: 4 }` — if an event is within 4
     hours, mention it ("Your 7 PM dinner is coming up").
   - `list_notifications { limit: 3 }` — only mention if there is at
     least one *urgent* notification (app category = MESSAGE / CALL /
     REMINDER). Not all notifications — unread Slack doesn't count.
   - If neither surfaces anything, say "Nothing urgent." / "急ぎの用は
     ありません。".
4. **Volume awareness**: if the current hour is in quiet-hours (see
   `quiet-hours` skill), run `set_volume` at 20 first so the greeting
   doesn't blast a sleeping household.

## What this skill does NOT do

- No music. If the user wants music they'll ask; auto-playing is
  disruptive when someone's hands are full of groceries.
- No weather briefing. That belongs to `morning-routine` /
  `weekend-morning`. Arrival home is past the moment when weather is
  actionable.
- No multi-device "scene" setup (TV, climate, etc). That is what the
  user's custom routines are for; adding it here would be
  invasive-by-default.

## Follow-ups

- If the user replies "what else?" within 10 seconds, offer
  `morning_briefing` / `get_weather` as voluntary next steps.
- If the user replies "quiet" / "shush" / "静かに", set volume to 10
  and skip any future audible prompts for the next 15 minutes.
