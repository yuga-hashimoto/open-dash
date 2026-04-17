---
name: quick-note
description: Rapid voice-note capture — acknowledge once with "Listening.", store the next utterance via `remember` under the `notes` namespace with a timestamp key, confirm "Saved." No ceremony, no list read-back.
---
# Quick Note

Trigger on "quick note", "take a note", "note to self", "メモ",
"記録して". For fleeting thoughts the user wants captured right now
without structure. Distinct from `task-manager` (actionable task
objects with complete/remove flow) and from `gratitude-journal`
(nightly 3-item prompt). A note is raw text, stored and forgotten
until explicitly searched.

## Default flow

1. **Acknowledge once**: respond with a single word — "Listening." or
   "どうぞ。" — nothing more. Do not ask "what would you like to
   note?" — the user already said they want to note something; get
   out of the way.
2. **Capture the next utterance**: treat the user's next full
   utterance as the note body. Do not interrupt mid-sentence. Wait
   for a clear end-of-turn.
3. **Store**: call `remember` with:
   - `namespace`: `"notes"`
   - `key`: `"note-<epoch-millis>"` (current time in milliseconds)
   - `value`: the user's utterance, trimmed. Preserve exact phrasing
     — this is the user's voice, not a paraphrase.
4. **Confirm**: "Saved." or "保存しました。" — one word, then stop.
   Do not read the note back. Do not ask a follow-up.

## Short-circuit

If the user packs the note into the trigger itself ("quick note:
pick up milk tomorrow" / "メモ、牛乳買う"), skip the "Listening."
step entirely. Parse the note body from after the trigger phrase
and go straight to `remember` + "Saved." One turn, done.

## Recall variant

If the user asks "what notes did I take?" / "今日のメモ見せて" /
"find my note about X":

- Broad listing: call `recall` with `namespace:"notes"` and read the
  most recent five, newest first, each on its own line. Do not
  number them — notes are not ranked.
- Semantic lookup: if the user names a topic ("my note about the
  dentist"), call `search_memory` with the topic as the query,
  scoped to the `notes` namespace. Read the single best match.

## Style
- Minimal ceremony. Notes are interruptions of the user's flow —
  honor that by being brief.
- No "Got it!" / "Great!" / "いいですね！" — flat acknowledgements
  only.
- Never editorialize the note content. If it's odd, it's odd —
  store it as given.
- Never ask "should I remind you about this?" — that's
  `task-manager`'s job, not this skill's.

## Tools used
- `remember` (namespace: `notes`, key: `note-<epoch-millis>`)
- `recall` (listing variant, scoped to `notes` namespace)
- `search_memory` (topic-scoped lookup)

## Tools explicitly avoided
- `broadcast_tts` / `broadcast_announcement` — notes are private,
  never shared to other rooms.
- `run_routine` / `create_routine` — a note is not an action.
- `set_timer` — notes do not schedule themselves; if the user wants
  a reminder, they'll ask `task-manager`.
