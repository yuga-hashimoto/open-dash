---
name: morning-routine
description: Wake-up flow — briefing, lights, ambient music, schedule check.
---
# Morning Routine

Trigger when the user says "good morning", "I'm awake", "let's start the day",
or when they tap a morning shortcut.

## Default flow
1. Call `morning_briefing` to fetch weather, news, and today's calendar in one
   shot. Summarize in 2–3 sentences (don't read every headline).
2. If there's a calendar event in the next 90 minutes, mention the next one by
   title and time.
3. If the user explicitly asks "and turn on the lights":
   - Call `execute_command` with `{device_type:"light", action:"turn_on"}`.
4. Offer one follow-up: "Want me to play something while you get ready?"
   If yes, call `execute_command` with `{device_type:"media_player",
   action:"media_play"}`.

## Style
- Skip the news summary if the briefing payload returns `news: null`.
- Don't dump raw JSON — translate weather like "It's 18 degrees and partly
  cloudy" not "temp 18 condition partly_cloudy".
- Wrap in 30 seconds of speech max. People don't want a TED talk before coffee.
