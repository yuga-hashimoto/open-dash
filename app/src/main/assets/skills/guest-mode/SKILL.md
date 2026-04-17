---
name: guest-mode
description: Relax the agent for visitors — silence wake-word + personal data tools, surface a welcome banner, restrict destructive actions.
---
# Guest Mode

Trigger on "guest mode", "guests are coming", "we have visitors",
"ゲストモード", "お客さん来るから". Turn off anything that either
(a) reads personal data or (b) takes destructive action without
explicit user confirmation.

This is a **behavioural** skill, not a wiring skill — the agent simply
declines to call sensitive tools while guest-mode is active. There is
no OS-level sandboxing; it's an instruction the agent follows.

## What stays on

- `get_weather` / `get_forecast`
- `set_volume` / `set_timer` / `cancel_timer`
- `execute_command` for lights / climate (general smart-home)
- `launch_app` for user-entertainment apps (music, photos)
- `broadcast_tts` if multi-room is enabled (so guests hear announcements)

## What pauses

- `list_memory` / `recall` / `semantic_memory_search` — never read back
  private memory notes in front of guests.
- `search_contacts` / `list_contacts` / `send_sms` — no personal comms.
- `list_recent_photos` / `take_photo` — no camera / gallery.
- `get_calendar_events` — no appointments read aloud.
- `reply_to_notification` — no reading other people's chats.
- `list_notifications` — ditto.
- `read_active_screen` / `tap_by_text` / `scroll_screen` / `type_text` —
  no automated tapping through random apps.
- `lock_screen` / `open_settings_page` — no surprise UI pivots.
- `broadcast_announcement` — don't pin messages on ambient screens guests
  might read.

If the user explicitly asks for one of the paused tools ("Alice, open my
photos for a sec"), **confirm once out loud**: "Guest mode is on — open
photos anyway?" Only proceed if they say yes.

## Default flow on activation

1. Speak: "Guest mode on. I'll stay out of your personal data until you
   tell me otherwise."
2. Set ambient lights to 80 % warm white (welcoming, not clinical).
3. Raise volume to 60 so guests can hear responses from across the room.
4. Leave a subtle banner via `broadcast_announcement` with
   `{ text: "Guest mode", ttl_seconds: 3600 }` so the household sees it
   and knows why the agent behaves differently.

## End of session

Trigger on "guest mode off", "guests are gone", "ゲストモード終わり":

1. Restore volume to prior (50).
2. Speak: "Guest mode off."
3. Clear any pinned banner via local clear.

## Style

- One-sentence confirmations. Don't re-explain the restrictions each
  time.
- If a guest speaks directly to the speaker, still respond, just stick to
  the "what stays on" list.

## Tools used
- `execute_command` (lights)
- `set_volume`
- `broadcast_announcement` (optional)
