---
name: rainy-day
description: Rainy-day check-in — inspect the forecast, and if rain is likely today, suggest a cozy indoor plan (playlist, close blinds/windows reminder) without taking destructive action.
---
# Rainy Day

Trigger on "rainy day", "rainy day routine", "is it raining",
"will it rain today", "is it going to rain", "should I bring an
umbrella", "雨の日", "今日雨降る", "今日雨降る?", "雨降りそう?",
"雨の日モード". Distinct from `dog-walk` (which peeks at weather on
the way out the door) and `morning-routine` (full wake-up brief):
rainy-day is a **stand-alone check-in** the user invokes when they
are weighing plans for the day. It must never autonomously close
blinds, lock doors, or start media — it only **suggests**.

## Default flow

1. **Look up the forecast.** Call `get_forecast` for the user's
   current location, covering the rest of today. If `get_forecast`
   is unavailable or returns no precipitation field, fall back to
   `get_weather` for the current hour. If both fail, reply with a
   single honest sentence and stop:
   - EN: "I couldn't pull the forecast just now — try again in a
     minute."
   - JA: 「今天気が取れませんでした。少ししたらもう一度聞いて
     ください。」

2. **Decide the branch from precipitation probability for today.**
   Use the highest `precipitation_probability` across the remaining
   hours of today (00:00 → 23:59 local). Treat missing values as 0.
   - `> 30 %` → **rainy branch** (go to step 3).
   - `≤ 30 %` → **dry branch** (go to step 4).

3. **Rainy branch — suggest, do not commit.** One short weather
   headline plus an optional plan. Speak at the user's **current**
   volume. Never bump. The plan is phrased as an offer the user can
   accept; do not fire the tools until they say yes.
   - Headline examples:
     - EN: "Looks like rain today — about 70 percent chance this
       afternoon."
     - JA: 「今日は雨になりそうです。午後は降水確率70%くらい。」
   - Offer template (pick one, keep it to one sentence):
     - EN: "Want me to queue an indoor playlist and remind you to
       close the blinds and windows?"
     - EN alternate: "Shall I start something cozy and nudge you
       about the windows?"
     - JA: 「室内モードのプレイリストと、窓・ブラインドを閉める
       リマインドを出しましょうか？」
     - JA alternate: 「ゆったりした音楽かけて、窓とブラインドの
       確認を促しましょうか？」
   - If the user says yes:
     1. Call `play_media_by_source` with a cozy-indoor query such
        as `{ query: "rainy day indoor playlist", source: "default" }`.
        Use whichever source is registered; do not hardcode a
        provider. If no media source is registered, say so in one
        sentence and skip this sub-step.
     2. Call `set_volume` with value `35` — low enough to sit
        under conversation, loud enough to feel present. Remember
        the prior volume so a later "rainy day over" can restore.
     3. Speak a single blinds/windows reminder — **do not** call
        `execute_command` on `cover` devices. The user may have
        laundry drying on the balcony, a cat on the sill, or a
        window they need left cracked. Suggestion only:
        - EN: "Heads up — might be worth closing the windows and
          dropping the blinds before the rain hits."
        - JA: 「雨が降る前に、窓とブラインドを閉めておくと安心
          です。」
     4. If the user explicitly confirms ("yes, close them" /
        「閉めて」), **then** call `execute_command` with
        `{ device_type: "cover", action: "close" }` for each
        registered cover. One short confirmation: "Blinds closing."
        / 「ブラインド閉めます。」 Never chain this without an
        explicit second confirmation.
   - If the user says no, declines, or stays silent for ~8
     seconds: acknowledge briefly ("Got it — just the heads-up
     then." / 「わかりました、天気だけお知らせでした。」) and end
     the session. Do not bump volume, do not queue media, do not
     call `execute_command`.

4. **Dry branch — short, reassuring.** One sentence, current
   volume, no follow-up offer. This is the common path and
   shouldn't feel heavy.
   - EN: "Looks dry today — about 15 percent chance of rain."
   - EN alternate: "No rain expected today."
   - JA: 「今日は雨の心配はなさそうです。」
   - JA alternate: 「晴れ〜曇りで、雨は多分降らないです。」
   Then stop. No media, no volume change, no reminder.

## Respect quiet-hours

Before speaking the offer in step 3, call `recall` with key
`quiet_hours.active`.
- If `true` and the user is simply asking "is it going to rain" —
  answer the headline only, skip the offer. A rainy-day plan at
  2am is not helpful.
- If `true` and the user explicitly asked for the full routine
  ("rainy day routine"), still honor the request but keep volume
  unchanged and skip `play_media_by_source` — substitute "I'll
  queue music when quiet-hours ends" and keep the blinds/windows
  reminder as a soft whisper.

## Stop / end session

Trigger on "rainy day over", "stop the rainy day music",
「雨の日モード終わり」, 「音楽止めて」:
1. Stop media playback via whatever stop mechanism the current
   `play_media_by_source` surfaced (most sources expose a
   companion stop via `execute_command` with
   `{ device_type: "media_player", action: "stop" }`). If unsure,
   skip this step rather than guess.
2. Restore volume to the pre-rainy-day level via `set_volume`.
3. One short acknowledgement: "Rainy-day mode off." /
   「雨の日モード終わりました。」

## Edge cases

- **Rain already falling** (`get_weather` shows active
  precipitation regardless of forecast): treat as rainy branch.
  Lead with "It's already raining — " then continue with the
  offer. Do not second-guess the sensor.
- **Forecast shows rain tomorrow, not today**: stay in the dry
  branch for today's question, but add one sentence — "Tomorrow
  looks wetter though." / 「明日の方が降りそうです。」 — so the
  user can plan.
- **User is traveling** (check `recall` for
  `travel_mode.active`): use the destination's forecast if the
  location is available; otherwise answer for the home location
  and say which. Do not silently mislead.
- **Multi-room household**: this is a single-speaker check-in. Do
  not `broadcast_tts` or `broadcast_announcement`. The other
  rooms didn't ask.

## Style

- Tone: unhurried neighbour glancing at the sky for you. Cozy,
  not alarmist — rain is not an emergency.
- EN: contractions fine. No exclamations. Numbers as words up to
  twenty, digits beyond.
- JA: polite-casual ("〜です / 〜ます"). No kaomoji, no weather
  onomatopoeia.
- Headlines ≤ 14 words EN, ≤ 30 characters JA. The user is
  deciding whether to head out; they don't want a lecture.
- Never promise what you haven't done. "I'll queue a playlist" is
  only said after the tool call succeeds.

## Tools used
- `get_forecast` (primary)
- `get_weather` (fallback + "already raining" check)
- `recall` (quiet-hours + travel-mode flags)
- `play_media_by_source` (only after user accepts the offer)
- `set_volume` (only on the rainy branch, only after accept)
- `execute_command` with `device_type: "cover"` — **suggest only,
  never autocommit**; requires an explicit second confirmation
  before firing

## Tools explicitly avoided
- `broadcast_tts`, `broadcast_announcement` — single-user
  check-in, no fan-out
- Autonomous `execute_command` on lights, locks, or thermostats —
  out of scope; the user asked about rain, not the whole house
- `clear_notifications` — not a do-not-disturb routine
