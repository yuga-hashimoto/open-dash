# Privacy & data flow

OpenDash is built as a **smart speaker that happens to be
local-first**, not a client for someone else's cloud. This page
enumerates exactly which data crosses the device boundary, where it
goes, and how to turn each channel off.

The privacy boundary is documented here. Before an assistant request is sent
to a remote provider, `RemoteDataPolicy` requires an explicit acknowledgement
of the cloud-provider description; Settings also offers a persistent local-only
routing switch. Physical first-use UX validation remains a release gate. See the
[smart-speaker audit](smart-speaker-audit.md).

If a capability is not on this page, it does not leave the tablet.

## What stays on device (always)

- **Voice**: raw microphone audio is intended to stay on the tablet for the
  local/offline paths. Android `SpeechRecognizer` is the default system
  recognizer and its actual network behavior depends on the installed Android
  recognition service; Whisper is an opt-in offline path that is compile-wired
  but not yet physically validated. Android TTS is the default local output;
  Piper native output remains unshipped and currently falls back to Android TTS.
- **Conversation history**: stored in in-memory `ConversationHistoryManager`
  during a session. Not persisted to disk. Not uploaded.
- **Memory entries** (`remember`/`recall` tools): Room DB on the
  tablet, never synced. Clearable via Settings → Memory → Clear all.
- **Skill and routine definitions**: asset files (bundled) or internal
  storage (user-installed). No telemetry on which skills fired.
- **Tool usage analytics**: persisted locally in a Room table; purely
  for the Settings → Analytics dashboard. Not uploaded.
- **Voice/power measurement session**: bounded in-memory counters, latency,
  battery percentage, thermal status names, and build metadata used by Voice
  Health and the real-device smoke procedure. It does not store transcripts,
  microphone audio, API keys, or wattage; export is an explicit redacted
  logcat action and is not uploaded by OpenDash.
- **Device state** (HA / SwitchBot / MQTT): intended to be fetched from the
  user's configured hub. The current provider wiring is still under the P22
  runtime audit; do not infer a successful connection from saved Settings alone.

## What can leave the device (opt-in + user-targeted)

Every channel below is either off by default or goes only to a
destination the user configured. We never ship credentials to the
app's developers.

| Channel              | What goes out                                         | Destination                            | How to disable                                |
| -------------------- | ----------------------------------------------------- | -------------------------------------- | --------------------------------------------- |
| Weather              | Lat/lon (from a city name geocode) or the city name. | `open-meteo.com` (no API key)          | Don't set `DEFAULT_LOCATION`; skip the card.  |
| News                 | RSS feed URL only (no user identity).                | User-chosen feed URL (default: NHK).   | Clear `NEWS_FEED_URL` preference.             |
| Web search           | Raw query string.                                     | DuckDuckGo (no API key, no account).   | Don't invoke `web_search` tool.               |
| Home Assistant       | All HA control traffic.                               | The user's own HA instance.            | Clear `HA_URL`.                               |
| SwitchBot            | All SwitchBot control traffic.                        | SwitchBot's servers (user's account).  | Clear `SWITCHBOT_TOKEN`.                      |
| Matter / MQTT        | Local-network device control.                         | Local network only.                    | Clear `MQTT_BROKER`.                          |
| OpenAI-compatible LLM| User prompt + reply.                                  | URL the user configured.               | Switch `AssistantProvider` to `EmbeddedLlm`.  |
| OpenClaw provider    | User prompt + reply.                                  | The user's OpenClaw instance.          | Switch `AssistantProvider`.                   |
| HermesAgent          | User prompt + reply.                                  | The user's Hermes endpoint.            | Switch `AssistantProvider`.                   |
| Multi-room broadcast | NDJSON/WebSocket envelopes signed with HMAC.          | Other speakers on the same LAN only.   | Turn off `MULTIROOM_BROADCAST_ENABLED`.       |
| Model downloads      | LiteRT model URL (one-off).                           | The URL the user pasted.               | Pre-load the model manually and skip the downloader. |

## Remote assistant guard

The first assistant turn that resolves to a non-local provider is blocked until
the user acknowledges the localized API/cloud-provider description in Settings.
Enabling local-only routing blocks API, OpenClaw, and Hermes assistant turns
even when those providers remain configured. Fast-path tool execution stays
available, and optional remote LLM polishing is skipped and replaced by the
local formatter when the gate is closed.

## What we never do

- **No usage telemetry** to our servers. There is no "our servers".
- **No crash reporting** uploaded anywhere. Timber logs are in-process only.
- **No advertising SDKs.** The app has zero ads-related dependencies.
- **No cloud sync for memory, routines, or skills.** Multi-device
  syncing would need an explicit external backend the user stands up
  themselves (tracked on the Phase 17 backlog as "ovos-personal-backend
  port" — not shipped).
- **No access to SMS / contacts / call logs / photos unless the user
  explicitly asks.** Those tools require runtime permissions and are
  only called when the LLM or a fast-path matcher decides they match
  the user's utterance.

## Secret storage

Per-connector credentials are stored in `EncryptedSharedPreferences`
(Android Keystore-backed). The audited keys are:

- `HA_TOKEN`
- `SWITCHBOT_TOKEN`, `SWITCHBOT_SECRET`
- `MQTT_PASSWORD`
- `OPENCLAW_API_KEY`
- `HERMES_AGENT_TOKEN`
- `MULTIROOM_SECRET` (HMAC shared secret)

Plaintext DataStore holds only non-sensitive settings (locale, wake
word text, UI toggles). If you find a secret in plain DataStore,
file a security issue — see [SECURITY.md](../SECURITY.md).

## Network-permitted domains (first-party defaults)

If you want to firewall the tablet to the absolute minimum set of
hosts, these are the defaults we reach by design:

- `open-meteo.com` (weather + geocoding)
- `geocoding-api.open-meteo.com`
- `duckduckgo.com` / `html.duckduckgo.com` (web search)
- `www3.nhk.or.jp` / `feeds.bbci.co.uk` / `news.ycombinator.com` (news)
- Whatever HA / SwitchBot / MQTT / OpenClaw / Hermes / model-download URL
  the user configures.

Everything else is user-added.

## How to verify for yourself

1. Settings → Analytics — the only network-heavy tools are
   `get_weather`, `get_forecast`, `web_search`, `get_news`. Their
   destinations are documented above.
2. `adb shell dumpsys netstats` on the tablet while the app is idle —
   expect near-zero traffic outside of the HA/SwitchBot poll interval.
3. `./gradlew lintDebug` — lint runs with a baseline and fails on new
   cleartext-network violations.
4. Source scan: every `HttpUrl` / `Retrofit` instance in the codebase
   lives under a provider wrapper whose job is documented above.

## Open issues

- Phase 16 offline STT/TTS remains partial: Whisper is compile-wired but
  unverified on hardware, while Piper native inference still needs its Android
  port. Android's installed recognition/TTS services are not a contractual
  offline guarantee.
- Phase 17 multi-room pairing word-phrase is in; a full
  challenge-response handshake is tracked on the ADR backlog.
- Model downloads currently trust the URL the user pasted. A future
  PR will bind download hashes into a build-time manifest.

## Related

- [SECURITY.md](../SECURITY.md) — responsible disclosure + threat
  model.
- [permissions.md](permissions.md) — every Android permission the app
  ever requests + why.
