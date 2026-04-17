---
name: where-did-i-put-it
description: Finds a misplaced item by searching the user's memory store for prior "I put X in Y" statements; if nothing matches, offers to remember the current location instead.
---
# Where Did I Put It?

Trigger on "where did I put", "where's my", "where is my",
"どこに置いた", "どこ行った", "どこだっけ". Paired with the
long-standing user memory tools (`remember` / `recall` /
`search_memory` / `semantic_memory_search`) — this skill wraps them
in a single conversational flow so users don't need to remember tool
names.

## Default flow

1. **Extract the search target** from the utterance — the noun after
   "my" / "the" / "わたしの" / "私の". If no clear target, ask once:
   "What item are you looking for?" / "何を探してますか？" — single
   clarifying question, not a dialog tree.
2. **Primary lookup**: call `semantic_memory_search { query: "<item>
   location" }` with `limit: 3`. Semantic search wins here because
   users say "keys" but previously stored "car keys on the shelf";
   keyword search would miss that.
3. **Literal lookup**: fall back to `search_memory { query: "<item>" }`
   if semantic returned zero results. Some users store notes like
   `"umbrella -> hallway closet"` which only substring search surfaces.
4. **Reply shape**:
   - Exactly one result → read it verbatim. Do not paraphrase. Keys on
     the hook is what they wrote; that is what we repeat.
   - Multiple plausible hits → pick the most recent, then mention the
     count: "On the shelf (2 other matches in your memory)".
   - Zero hits → single-sentence fallback: "I don't have a note about
     `<item>`. Want me to remember where you put it?" If the user
     answers "yes" + location, call `remember { text: "<item> is in
     <location>" }` and confirm.
5. **Never invent a location.** The temptation for an LLM here is
   real — if memory is empty, say so and offer to store. Hallucinated
   locations are worse than admitting ignorance.

## What this skill does NOT do

- **No visual / camera search.** Photos of cluttered desks aren't a
  reliable locator and the camera tool is too heavy-weight for a
  "where are my keys" flow.
- **No Bluetooth tracker integration** (AirTag, Tile, etc.). Those
  have their own apps; this skill wraps text-based memory only.
- **No cross-device search.** Multi-room memory sync is deliberately
  out of scope (see `docs/privacy.md` — memory stays on this tablet).

## Follow-ups

- If the user replies "I moved it to X" after a hit, append a new
  `remember` entry with the new location and an implicit timestamp.
  Do not overwrite the old entry — memory is append-only so the
  user's own trail of where an item used to be stays intact.
- If the user replies "forget that" after any step, call
  `forget { query: "<item>" }` and confirm.
