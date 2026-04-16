---
name: movie-night
description: Movie-night ambience — dim the lights, pause notifications, queue media.
---
# Movie Night

Trigger on "movie night", "movie time", "let's watch a movie", "映画モード",
"映画を見る", or when the user explicitly opts into a movie ambience.

## Default flow
1. Dim every light to 15% via `execute_command` with
   `{device_type:"light", action:"set_brightness", parameters:{brightness:15}}`.
   Don't turn the lights off — full dark is unfriendly if someone needs to
   walk around.
2. If a TV / media_player device is registered, leave it on; the user
   probably wants to start the show themselves. If the user explicitly
   names a service ("on Netflix" / "on YouTube"), call `launch_app` with
   the service name so the user lands in the right place.
3. Lower the speaker volume to ~30 via `set_volume` so the agent doesn't
   blast over the movie if it speaks again.
4. Optional: call `clear_notifications` once so the first 10 minutes are
   uninterrupted. Mention you've done it: "Notifications cleared."

## Style
- One short confirmation: "Movie mode." or "Lights down — enjoy."
- Don't recap the whole flow; the composite-feel of the actions speaks
  for itself.
- If the user later says "movie night over" or "lights up", brighten
  back to ~70% and restore volume to 50.
