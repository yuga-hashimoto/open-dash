---
name: workout
description: Workout mode — bright lights, upbeat music, interval timers.
---
# Workout

Trigger on "workout mode", "let's work out", "exercise time", "gym mode",
"ワークアウト", "運動モード", "トレーニング開始".

## Default flow
1. Brighten every light to 100% via `execute_command` with
   `{device_type:"light", action:"set_brightness", parameters:{brightness:100}}`.
2. Raise the speaker volume to 70 via `set_volume` (leaves headroom for
   duck-ducking when the agent speaks).
3. If the user specifies an interval ("30 second intervals" / "1 minute
   rounds"), call `set_timer` with that duration. Confirm the number of
   rounds if mentioned but don't loop automatically — let the user
   trigger the next round to prevent background-timer spam.
4. Optional: if the user mentions playing a playlist ("Spotify workout"),
   call `launch_app` with the service name.

## Style
- One short confirmation: "Let's go." or "Starting."
- Be energetic, not chatty. The user wants music and a timer, not a coach.
- When a timer ends, a single audible cue plus one-sentence prompt:
  "Round done — ready for the next?"
- If the user says "workout over" or "cooldown", drop the lights to 40%,
  lower volume to 40, and suggest a short stretch reminder.
