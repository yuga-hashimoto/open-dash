---
name: bedtime-routine
description: Wind-down — pause media, dim lights, set alarm, brief evening summary.
---
# Bedtime Routine

Trigger on "good night", "I'm going to bed", "time to sleep", or when the user
opts in to the night quiet suggestion.

## Default flow
1. Call `goodnight` — composite that turns off all lights, pauses media, and
   cancels active timers in one shot. Trust the result; don't second-guess.
2. If the user mentions an alarm time ("wake me up at 7"), call `set_timer`
   with the appropriate duration in seconds — there's no calendar/alarm tool
   yet, so use a long timer with a clear label.
3. Optional: call `evening_briefing` if the user asks "what do I need to
   know for tomorrow?".

## Style
- One short confirmation: "Goodnight." or "Sleep well."
- Don't ramble about what got turned off — the composite already did the work.
- Keep the audio cues quiet. The whole point is wind-down.
