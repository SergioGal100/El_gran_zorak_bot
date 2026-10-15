# El Gran Zorak — Specification

A Discord music bot that joins the caller's voice channel and plays audio from
local files and online sources (including YouTube).

## Stack

| Component | Library | Version |
|---|---|---|
| Discord API | Discord4J (`com.discord4j:discord4j-core`) | 3.3.3 |
| Audio loading / decoding | LavaPlayer (`dev.arbjerg:lavaplayer`) | 2.2.7 |
| YouTube resolving | yt-dlp (external program, needs Deno) | latest (`yt-dlp -U`) |
| Voice encryption (DAVE) | libdave natives (`moe.kyokobot.libdave:natives-win-x86-64`) | 0.1.2 |
| JSON | Jackson, pinned via `jackson-bom` | 2.22.1 |
| Logging | SLF4J + Logback | 2.0.20 / 1.5.18 |

Build: Maven. Java: 64-bit JVM required (the DAVE native library is 64-bit only).

External programs (for YouTube): `winget install yt-dlp.yt-dlp` and `winget install DenoLand.Deno`
(yt-dlp needs a JavaScript runtime to read YouTube). Restart VS Code afterwards so PATH updates.

## Configuration

| Setting | Where | Notes |
|---|---|---|
| `DISCORD_TOKEN` | Environment variable | Bot token. Set in the **App** launch config in `.vscode/launch.json`. Never commit it. |
| `YTDLP_PATH` | Environment variable (optional) | Full path to `yt-dlp.exe` if it isn't on PATH. Defaults to `yt-dlp`. |
| Log level | `src/main/resources/logback.xml` | `INFO` by default; switch to `DEBUG` for network traces. |

### Discord setup

- **Gateway intents:** `GUILDS`, `GUILD_MESSAGES`, `GUILD_VOICE_STATES`, `MESSAGE_CONTENT`.
  `MESSAGE_CONTENT` must also be enabled in the Discord Developer Portal (Bot → Privileged Gateway Intents).
- **Permissions in the voice channel:** View Channel, Connect, Speak.
- **Permissions in the text channel:** View Channel, Send Messages, Read Message History.

## Commands

Commands are plain-text messages in a server text channel. DMs are ignored.

| Command | Behavior |
|---|---|
| `~play <source>` | Loads `<source>` and queues it. If nothing is playing, it starts immediately. The bot joins the caller's voice channel if it isn't already there. |
| `~skip` | Stops the current track and starts the next one in the queue. |

### `~play` rules

1. The caller must be in a voice or stage channel. Otherwise the bot replies
   *"Join a voice channel first, then use ~play."*
2. Angle brackets around the source are stripped, so `<https://youtu.be/...>` works.
3. Replies:
   - Track loaded → *"Adding to queue `<title>`"*
   - Playlist loaded → queues the selected track, or the first track if none is selected,
     and replies *"Adding to queue `<title>` (first track of playlist `<name>`)"*
   - Nothing found → *"Nothing found by `<source>`"*
   - Load error, or yt-dlp failure for YouTube → *"Could not play: `<reason>`"*
   - Voice join error or timeout (20 s) → *"Voice error: `<type>` - `<message>`"*

## Supported sources

| Source | Example |
|---|---|
| Local file (absolute path) | `D:\music\song.flac` |
| YouTube video | `https://www.youtube.com/watch?v=dQw4w9WgXcQ`, `https://youtu.be/dQw4w9WgXcQ` |
| YouTube Music | `https://music.youtube.com/watch?v=...` |
| SoundCloud, Bandcamp, Vimeo, Twitch, direct HTTP audio links | Any URL LavaPlayer's remote sources accept |

LavaPlayer's built-in YouTube source is broken, so it's excluded. YouTube links are handled by
`YtDlpResolver`: it runs yt-dlp to get the title and a direct audio URL, and LavaPlayer's HTTP
source plays that URL. No Google account is needed.

## Architecture

```
App (Discord events, commands)
 ├─ YtDlpResolver               ── YouTube link -> title + direct audio URL (runs yt-dlp)
 ├─ AudioPlayerManager (shared)  ── source managers: remote (HTTP, SoundCloud, ...), local
 └─ GuildMusicManager (one per guild, keyed by guild ID)
     ├─ AudioPlayer       ── LavaPlayer player producing Opus frames
     ├─ TrackScheduler    ── FIFO queue; advances on track end; logs track events
     └─ D4jAudioProvider  ── hands Opus frames from the player to Discord4J's voice connection
```

| File | Responsibility |
|---|---|
| `App.java` | Login, intents, message listener, command parsing, voice channel lookup, joining voice, loading tracks. |
| `GuildMusicManager.java` | Bundles the player, scheduler and provider for one guild. |
| `TrackScheduler.java` | Queue, auto-advance, and logs for track start, end, error and stuck events. `titleOf()` gives the display title. |
| `YtDlpResolver.java` | Detects YouTube links and runs yt-dlp (30 s timeout) to get the title and audio URL. |
| `D4jAudioProvider.java` | Bridge between LavaPlayer frames and Discord4J's `AudioProvider`. |

### Flow of `~play`

1. `MessageCreateEvent` → parse the command.
2. Look up the caller's voice state → `AudioChannel`.
3. YouTube link → `YtDlpResolver.resolve()`; the direct URL is loaded and the title stored as track user data.
4. `loadItemOrdered` on LavaPlayer's loader thread.
5. On load: join the voice channel if needed (DAVE-encrypted, 20 s timeout), then `scheduler.queue(track)`.
6. Discord4J pulls 20 ms Opus frames from `D4jAudioProvider`.

## Known limitations

- **YouTube depends on yt-dlp:** when YouTube changes, update it with `yt-dlp -U`.
- **Expiring links:** the direct audio URL expires after a few hours, so a YouTube track queued
  long before it plays can fail.
- **YouTube playlists:** only the single video in the link is played (`--no-playlist`).
- **Playlists:** only one track from a playlist is queued.
- **No commands for** stop, pause, queue listing, volume, or leaving the channel.
  The bot stays in voice after the queue ends.
- **Single platform natives:** the pom includes the Windows x64 DAVE native only.
  Running on Linux or macOS needs the matching `moe.kyokobot.libdave:natives-*` artifact.
- **Local paths:** any user in the server can ask the bot to play any file path
  on the host machine. Only run it in servers you trust.

## Dependency notes

- **Jackson pin:** LavaPlayer pulls Jackson 2.15.2, but Discord4J 3.3.3 needs 2.22.1.
  Mixing versions breaks JSON parsing, so `jackson-bom` is imported in `dependencyManagement`.
- **DAVE:** Discord requires end-to-end encrypted voice (close code `4017` without it).
  The native version must match the `impl-jni` version Discord4J uses (0.1.2 for 3.3.3).
