package com.dreganwolves;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import dev.lavalink.youtube.clients.AndroidVr;
import dev.lavalink.youtube.clients.Music;
import dev.lavalink.youtube.clients.Tv;
import dev.lavalink.youtube.clients.Web;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.EventDispatcher;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.VoiceState;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.core.object.entity.channel.AudioChannel;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        GatewayDiscordClient client = DiscordClient.create(System.getenv("DISCORD_TOKEN"))
            .gateway()
            .setEnabledIntents(IntentSet.of(
                Intent.GUILDS,              // NEW: needed for guild, channel and voice state data
                Intent.GUILD_MESSAGES,
                Intent.GUILD_VOICE_STATES,
                Intent.MESSAGE_CONTENT))
            .login()
            .block();

        new App(client.getSelfId()).registerListeners(client.getEventDispatcher());

        log.info("Bot is online");
        client.onDisconnect().block();
    }

    private final AudioPlayerManager playerManager;
    private final Map<Long, GuildMusicManager> musicManagers;
    private final Snowflake selfId;

    private App(Snowflake selfId) {
        this.selfId = selfId;
        this.musicManagers = new ConcurrentHashMap<>();

        this.playerManager = new DefaultAudioPlayerManager();
        // YouTube: use the maintained youtube-source manager and skip LavaPlayer's broken built-in one
        // Tv is the client that plays with OAuth; the others load titles and playlists
        dev.lavalink.youtube.YoutubeAudioSourceManager youtube =
                new dev.lavalink.youtube.YoutubeAudioSourceManager(true,
                        new Music(), new Web(), new AndroidVr(), new Tv());
        // OAuth: first run with no token prints a google.com/device code in the log;
        // after signing in, the log prints a refresh token -> put it in YOUTUBE_REFRESH_TOKEN
        youtube.useOauth2(System.getenv("YOUTUBE_REFRESH_TOKEN"), false);
        playerManager.registerSourceManager(youtube);
        AudioSourceManagers.registerRemoteSources(playerManager,
                com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager.class);
        AudioSourceManagers.registerLocalSource(playerManager);
    }

    private void registerListeners(EventDispatcher eventDispatcher) {
        // NEW: error handler, so one exception doesn't silently kill the listener
        eventDispatcher.on(MessageCreateEvent.class)
            .subscribe(event -> {
                try {
                    onMessageReceived(event);
                } catch (Exception e) {
                    log.error("Error handling message", e);
                }
            }, err -> log.error("Message listener died", err));
    }

    private synchronized GuildMusicManager getGuildAudioPlayer(Guild guild) {
        return musicManagers.computeIfAbsent(guild.getId().asLong(),
                id -> new GuildMusicManager(playerManager));
    }

    private void onMessageReceived(MessageCreateEvent event) {
        Message message = event.getMessage();
        String content = message.getContent();

        MessageChannel channel = message.getChannel().block();
        if (!(channel instanceof TextChannel)) {
            return;
        }
        TextChannel textChannel = (TextChannel) channel;
        String[] command = content.split(" ", 2);

        if ("~play".equals(command[0]) && command.length == 2) {
            // NEW: find the voice channel of the person who typed the command
            AudioChannel voiceChannel = event.getMember()
                    .flatMap(this::getUserVoiceChannel)
                    .orElse(null);

            if (voiceChannel == null) {
                sendMessageToChannel(textChannel, "Join a voice channel first, then use ~play.");
                return;
            }
            // strip <...> that Discord users add around links to hide the preview
            String trackUrl = command[1].trim().replaceAll("^<(.*)>$", "$1");
            loadAndPlay(textChannel, voiceChannel, trackUrl);
        } else if ("~skip".equals(command[0])) {
            skipTrack(textChannel);
        }
    }

    private java.util.Optional<AudioChannel> getUserVoiceChannel(Member member) {
        java.util.Optional<AudioChannel> result = member.getVoiceState()
                .flatMap(VoiceState::getChannel)
                .blockOptional();
        log.info("Voice channel for {}: {}", member.getUsername(),
                result.map(c -> c.getName() + " (" + c.getType() + ")").orElse("none found"));
        return result;
    }

    private void loadAndPlay(TextChannel channel, AudioChannel voiceChannel, final String trackUrl) {
        Guild guild = channel.getGuild().block();
        GuildMusicManager musicManager = getGuildAudioPlayer(guild);

        playerManager.loadItemOrdered(musicManager, trackUrl, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                sendMessageToChannel(channel, "Adding to queue " + track.getInfo().title);
                play(channel, voiceChannel, musicManager, track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                AudioTrack firstTrack = playlist.getSelectedTrack();
                if (firstTrack == null) {
                    firstTrack = playlist.getTracks().get(0);
                }
                sendMessageToChannel(channel, "Adding to queue " + firstTrack.getInfo().title
                        + " (first track of playlist " + playlist.getName() + ")");
                play(channel, voiceChannel, musicManager, firstTrack);
            }

            @Override
            public void noMatches() {
                sendMessageToChannel(channel, "Nothing found by " + trackUrl);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                log.warn("Load failed for {}", trackUrl, exception);
                sendMessageToChannel(channel, "Could not play: " + exception.getMessage());
            }
        });
    }

    private void play(TextChannel channel, AudioChannel voiceChannel,
                      GuildMusicManager musicManager, AudioTrack track) {
        // NEW: errors here used to vanish inside LavaPlayer's thread; now they're reported
        try {
            boolean alreadyThere = Boolean.TRUE.equals(
                    voiceChannel.isMemberConnected(selfId).block());

            if (!alreadyThere) {
                log.info("Joining voice channel {}", voiceChannel.getName());
                // timeout: without it, a stuck voice handshake hangs here forever with no error
                voiceChannel.join().withProvider(musicManager.provider)
                        .timeout(java.time.Duration.ofSeconds(20))
                        .block();
                log.info("Joined voice channel {}", voiceChannel.getName());
            }

            musicManager.scheduler.queue(track);
        } catch (Exception e) {
            log.error("Failed to join voice or start playback", e);
            sendMessageToChannel(channel, "Voice error: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
        }
    }

    private void skipTrack(TextChannel channel) {
        GuildMusicManager musicManager = getGuildAudioPlayer(channel.getGuild().block());
        musicManager.scheduler.nextTrack();
        sendMessageToChannel(channel, "Skipped to next track.");
    }

    private void sendMessageToChannel(TextChannel channel, String message) {
        try {
            channel.createMessage(message).block();
        } catch (Exception e) {
            log.warn("Failed to send message {} to {}", message, channel.getName(), e);
        }
    }
}
