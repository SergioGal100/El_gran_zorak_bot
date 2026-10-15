# El Gran Zorak

A Discord music bot that joins your voice channel and plays local files, YouTube links
and other online audio.

## Requirements

### Software
| Requirement | Why | Install |
|---|---|---|
| Java, **64-bit**, version 8 or newer (21 recommended) | Runs the bot. Must be 64-bit for Discord's voice encryption library. | Check with `java -version` (should say "64-Bit") |
| Maven | Builds the project and downloads libraries | Included with the VS Code Java extensions |
| yt-dlp | Gets audio from YouTube links | `winget install yt-dlp.yt-dlp` |
| Deno | Needed by yt-dlp to read YouTube | `winget install DenoLand.Deno` |

Restart VS Code after installing yt-dlp and Deno so it finds them.

### Discord
1. Create a bot in the [Discord Developer Portal](https://discord.com/developers/applications) and copy its token.
2. Under **Bot → Privileged Gateway Intents**, turn on **Message Content Intent**.
3. Invite the bot to your server with these permissions:
   - Text channels: View Channel, Send Messages, Read Message History
   - Voice channels: Connect, Speak

## Configuration
Settings are environment variables, set in the **App** configuration in `.vscode/launch.json`:

| Variable | Required | Description |
|---|---|---|
| `DISCORD_TOKEN` | Yes | Your bot token. Never commit it (`.vscode` is in `.gitignore`). |
| `YTDLP_PATH` | No | Full path to `yt-dlp.exe` if it isn't on PATH. |

## Running
1. Open the folder in VS Code and run **Java: Reload Projects**.
2. In **Run and Debug** (Ctrl+Shift+D), choose **App** and press F5.
3. Wait for `Bot is online` in the console.

## Commands
| Command | What it does |
|---|---|
| `~play <file path or URL>` | Joins your voice channel and plays or queues the track |
| `~skip` | Skips to the next track |

Examples:
- `~play D:\music\song.flac`
- `~play https://www.youtube.com/watch?v=...`

## Troubleshooting
| Problem | Fix |
|---|---|
| `NullPointerException: token` at startup | Start with the **App** configuration, which sets `DISCORD_TOKEN` |
| "Join a voice channel first" | Join a voice channel, then use `~play` |
| `Required library was not found` (dave-jvm) | Use 64-bit Java |
| "Could not play: yt-dlp is not installed…" | Install yt-dlp and Deno, then restart VS Code |
| YouTube videos start failing | Update yt-dlp: `yt-dlp -U` |

## More details
See [SPECS.md](SPECS.md) for architecture, library versions and known limitations.
