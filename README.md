# Game Activity Monitoring Service

Monitors Rust and Space Engineers and exposes activity data as JSON. Presentation (labels, Markdown, colours and pagination) belongs to clients such as [DiscordBotSE](https://github.com/Roo831/DiscordBotSE).

## Build and run

Requires Java 21. On Windows use `gradlew.bat`; on Linux/macOS use `sh ./gradlew`.

```powershell
./gradlew.bat test bootJar
$env:API_KEY = 'replace-with-a-secret'
./gradlew.bat bootRun
```

The service listens on port 8083. Server addresses, query ports and log paths are configured in `src/main/resources/application.yml` and can be overridden with Spring environment variables, for example `RUST_MONITOR_LOG_FILE_PATH` and `SE_MONITOR_LOG_FILE_PATH`. Set writable log paths and `WATCHLIST_STATE_FILE` when running outside Docker. An empty `RUST_MONITOR_SERVER_IP` / `SE_MONITOR_SERVER_IP` disables the corresponding poller, which is useful for local tests.

The watchlist uses an ordinary JSON file, with no database or additional storage dependency. By default `/app/logs/watchlist.json` is persisted by the existing `./server_logs:/app/logs` Docker bind mount. Keep this directory when rebuilding or restarting containers. The current Dockerfile packages a prebuilt JAR; build it first with `sh ./gradlew clean test bootJar` (or `gradlew.bat` on Windows). Compose uses the published `roo831/game-activity-monitoring-service` image; after publishing the updated image, run `docker compose pull` and `docker compose up -d`. Runtime logs, state and environment secrets are excluded from the Docker build context.

## Storage and logging

`ActivityRepository` is the activity persistence boundary (`findAll`, `saveAll`). `LogFileActivityRepository` is the active implementation and retains the existing UTF-8 `[timestamp] [CONNECT|DISCONNECT] nickname` journal. The tracker and analytics share one repository instance per game, selected in `MonitorConfig`. A later database implementation can replace this adapter without adding filesystem access to the business services. No database, JDBC driver, or migration is introduced.

Journal batches create missing directories and validate every event before writing. Append failures are reported and the tracker keeps its previous committed presence, so the next successful poll retries missing transitions. Partial batch writes attempt to truncate back to the previous file size. Repository reads preserve existing logs and skip malformed rows, including impossible dates. Do not run multiple processes writing the same journal.

Both application diagnostics and SLF4J library messages use Log4j 2 through Spring Boot's Log4j2 starter. Operational logs go to the console and a separate file: `APPLICATION_LOG_FILE` defaults to `./logs/game-monitoring.log`; Compose sets `/app/logs/game-monitoring.log` in the existing persisted volume. These messages never enter the activity journal. Standard `logging.level.*` settings control verbosity. Log4j versions are managed by the Spring Boot dependency platform.

## JSON API

Every request requires the `X-API-KEY` header matching `API_KEY`. Use `/api/v1/analytics/rust` or `/api/v1/analytics/engineers` as the base path.

| Endpoint | Parameters | Response |
| --- | --- | --- |
| `GET /stats` | `nickname`, `days` (default 7) | `PlayerActivityReport` |
| `GET /raw` | `nickname`, `days` (default 3) | `RawDataReport` |
| `GET /nicknames` | `days` (default 7) | `NicknameReport` |
| `POST /watchlist` | `nickname`, `userId`, `channelId` | `WatchRegistration` |
| `GET /watchlist` | `userId`, `channelId` | Array of `WatchSubscription` |
| `DELETE /watchlist/{subscriptionId}` | `userId`, `channelId` | HTTP 204, owner/channel scoped and idempotent |
| `GET /watchlist/pending` | optional paired `afterObservedAt`, `afterEventId` cursor | Array of `WatchNotification`, oldest first, at most 100 |
| `POST /watchlist/pending/{eventId}/ack` | none | HTTP 204, game scoped and idempotent |
| `GET /similar` | `nickname`, `days` (default 7) | `SimilarActivityReport` |

`days` must be 1–365; a trimmed nickname must contain 1–128 characters. Watchlist nicknames reject control characters and normalize Unicode to NFC; matching is case insensitive. `userId` and `channelId` must be positive decimal Discord snowflakes. Encode query parameters rather than concatenating nicknames into a URL.

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

## One-shot watchlist

`WatchSubscription` has `id`, `nickname`, `userId`, `channelId`, UTC `createdAt`, and `status` (`WAITING` or `PENDING`). `WatchRegistration` contains `subscription` and `alreadyOnline`. `WatchNotification` has `eventId`, `subscriptionId`, `nickname`, `userId`, `channelId`, and UTC `observedAt`. IDs are stable UUID strings; timestamps are ISO instants. Subscriptions are independent per normalized nickname, user, channel, and game, with a limit of 25 active subscriptions per user/game, including pending ones. Registering the same subscription is idempotent and never clears a pending event.

Successful server polls turn matching waiting subscriptions into pending events. Registering a player observed online within the last two minutes queues an event immediately and returns `alreadyOnline=true`. Failed or incomplete polls preserve the last successful presence and generate no inferred disconnections. After restart presence is unknown until the first successful poll, which can fulfill waiting subscriptions for anyone then present. Named A2S players are included regardless of session duration; entries with control characters in the nickname are skipped after consuming their full packet entry. A2S does not establish Steam identity or distinguish named bots reliably. Split/compressed or structurally malformed A2S replies are rejected rather than treated as partial presence. A watchlist file-write failure preserves pending events and leaves activity logging independent; subscriptions can be fulfilled by the next successful poll after storage recovers. Case/NFC spelling changes keep the recorded session open until the actual exit.

Pending reads are non-destructive. Events sort by observation time, then event ID. To read beyond 100, pass both `afterObservedAt` (ISO instant) and `afterEventId` from the last event of the preceding page. The cursor remains valid if that event has been acknowledged or cancelled. Advance past failed deliveries during each dispatch pass, then start without a cursor on the next pass to retry them; otherwise permanent failures can block newer events. A client must save its successful-delivery receipt before acknowledging the event. Acknowledgement removes the one-shot subscription; repeated acknowledgement is safe. Cancellation removes only a matching owner/channel/game subscription and its pending event. Pending events survive restart. File-write failures return HTTP 500 with code `WATCHLIST_UNAVAILABLE`.

Each change writes a complete JSON snapshot to a temporary file in the same directory, flushes it to storage, and atomically replaces the live file before publishing the new in-memory state. The filesystem must support atomic replacement (for example NTFS or ext4); unsupported replacement fails the change and keeps the previous state. Invalid or inconsistent JSON stops startup without overwriting the file. Back up the state file together with the logs; no migration or external storage service is required.

The deployment supports one backend process and one bot instance per game. Multiple senders would need a delivery lease before deployment; a successful Discord send followed by a process failure before saving its receipt can still duplicate a notification.

## Similar activity

`/similar` returns `nickname`, `days`, `periodStart`, `periodEnd`, `timeZone`, `totalSeconds`, and `players`. Each candidate has `nickname`, `totalSeconds`, `overlapSeconds`, `sharedDays`, `targetOverlapPercent`, `playerOverlapPercent`, `similarityPercent`, `sharedSessions`, and `rankingScore`. `similarityPercent` remains `overlap / (target total + player total - overlap) * 100`, a Jaccard percentage of recorded time.

A meaningful shared session requires at least 15 minutes of total overlap within one merged, recorded target session. Candidate reconnects within that target session count once. Players without any such session are omitted; the target is always excluded. `rankingScore = similarityPercent * sharedSessions`, so repeated overlap matters without automatically promoting continuously online players. Sort by ranking score descending, Jaccard descending, overlap duration descending, then nickname ascending. Scores are points and can exceed 100; they are not probabilities. Durations and shared dates still include all overlap for an included candidate.

Equal join/exit timestamps are not used as evidence: monitor startup/shutdown can create them for unrelated players. The result is a co-presence heuristic, not a confirmed teammate list or friendship claim. Missing historical disconnects remain uncertain; this change does not repair old logs or establish Steam identity. Clients paginate every included candidate.

## Coordinated upgrade

**Breaking watchlist change:** registration now requires recipient IDs; pending reads return durable events and require explicit acknowledgement. The former in-memory prototype had no durable subscriptions to migrate. Update clients together with DiscordBotSE: pause the bot, rebuild/deploy the backend, then rebuild/deploy the bot. `/stats`, `/raw`, `/nicknames` and API-key authentication keep their existing JSON contracts.

Tests use temporary logs/JSON files and disable both scheduled game pollers. They verify restart, corrupted state and failed-write preservation. Query-parser tests use a local UDP fixture; they do not connect to live game servers or Discord.
