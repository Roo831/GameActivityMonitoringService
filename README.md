# Game Activity Monitoring Service

Monitors Rust and Space Engineers and exposes activity data as JSON. Presentation (labels, Markdown, colours and pagination) belongs to clients such as [DiscordBotSE](https://github.com/Roo831/DiscordBotSE).

## Build and run

Requires Java 21. On Windows use `gradlew.bat`; on Linux/macOS use `sh ./gradlew`.

```powershell
./gradlew.bat test bootJar
$env:API_KEY = 'replace-with-a-secret'
./gradlew.bat bootRun
```

The service listens on port 8083. Server addresses, query ports and log paths are configured in `src/main/resources/application.yml` and can be overridden with Spring environment variables, for example `RUST_MONITOR_LOG_FILE_PATH` and `SE_MONITOR_LOG_FILE_PATH`. Set writable log paths when running outside Docker. An empty `RUST_MONITOR_SERVER_IP` / `SE_MONITOR_SERVER_IP` disables the corresponding poller, which is useful for local tests.

## JSON API

Every request requires the `X-API-KEY` header matching `API_KEY`. Use `/api/v1/analytics/rust` or `/api/v1/analytics/engineers` as the base path.

| Endpoint | Parameters | Response |
| --- | --- | --- |
| `GET /stats` | `nickname`, `days` (default 7) | `PlayerActivityReport` |
| `GET /raw` | `nickname`, `days` (default 3) | `RawDataReport` |
| `GET /nicknames` | `days` (default 7) | `NicknameReport` |
| `POST /watchlist` | `nickname` | `{ "nickname": "Player", "watching": true }` |
| `GET /watchlist/pending` | none | Array of nicknames |

`days` must be 1–365; a trimmed nickname must contain 1–128 characters. Nickname matching is case insensitive. Encode query parameters rather than concatenating nicknames into a URL.

Example `/stats` response:

```json
{
  "nickname": "Player",
  "days": 7,
  "periodStart": "2026-10-02T12:00:00",
  "periodEnd": "2026-10-09T12:00:00",
  "timeZone": "Europe/Moscow",
  "totalSeconds": 5400,
  "peakHour": 10,
  "dailyActivity": [{
    "date": "2026-10-08",
    "totalSeconds": 5400,
    "sessions": [{
      "start": "2026-10-08T10:00:00",
      "end": "2026-10-08T11:30:00",
      "durationSeconds": 5400,
      "ongoing": false
    }]
  }]
}
```

All durations are integer **seconds**, without rounding to display units. Timestamps are ISO local date/time in the service's `timeZone`, matching the existing log timestamps; no UTC offset is embedded in them. The rolling window starts at `periodEnd - days`. Sessions crossing the window boundary are clipped; sessions crossing midnight are split by date. An unmatched connection is counted up to `periodEnd`, with only its final segment marked `ongoing`. This reflects the log, not a live presence check. `peakHour` is the hour with the greatest recorded duration (earliest hour wins ties); it is `null` for no activity. Empty reports have zero totals and empty arrays.

`/raw` returns the same `nickname`, `days`, `periodStart`, `periodEnd`, `timeZone` metadata plus `events`: an array of `{ "timestamp": "2026-10-08T10:00:00", "action": "CONNECT", "nickname": "Player" }`. Actions are `CONNECT` or `DISCONNECT`; events retain log order and are not truncated for Discord message limits.

`/nicknames` returns `days`, `periodStart`, `periodEnd`, `timeZone` and a sorted `nicknames` array. It includes players with events in the window and players still connected according to earlier log entries.

Invalid values return HTTP 400 with `{ "code": "INVALID_REQUEST", "message": "..." }`. Missing/wrong keys return HTTP 401 with code `UNAUTHORIZED`. Unreadable logs return HTTP 500 with code `LOGS_UNAVAILABLE`; missing logs return an empty report.

## Coordinated upgrade

**Breaking response change:** these existing `/api/v1` report endpoints previously returned formatted text. Deploy this change together with the JSON-consuming DiscordBotSE change. Pause the bot, deploy the backend, then deploy/restart the bot. Existing text-consuming clients must update their parsing. Endpoint paths, request parameters and API key authentication remain the same.

Tests use temporary logs and disable both game pollers; they do not connect to live game servers.
