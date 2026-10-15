package com.dreganwolves;

import java.nio.ByteBuffer;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;

import discord4j.voice.AudioProvider;

public class D4jAudioProvider extends AudioProvider {
    
    private final MutableAudioFrame frame = new MutableAudioFrame();
    private final AudioPlayer player;

    public D4jAudioProvider(AudioPlayer player) {
        super(ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()));
        this.player = player;
        this.frame.setBuffer(getBuffer());
    }

    @Override
    public boolean provide() {
        boolean didProvide = player.provide(frame);
        if (didProvide) getBuffer().flip();
        return didProvide;
    }
}
