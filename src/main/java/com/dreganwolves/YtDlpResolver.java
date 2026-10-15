package com.dreganwolves;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Turns a YouTube link into a title and a direct audio URL by running yt-dlp.
 * LavaPlayer's own YouTube support can't keep up with YouTube's changes; yt-dlp can.
 */
public class YtDlpResolver {

    private static final Pattern YOUTUBE_URL = Pattern.compile(
            "^https?://([a-z0-9-]+\\.)?(youtube\\.com|youtu\\.be)/.*", Pattern.CASE_INSENSITIVE);
    private static final long TIMEOUT_SECONDS = 30;

    public static class Resolved {
        public final String title;
        public final String audioUrl;

        Resolved(String title, String audioUrl) {
            this.title = title;
            this.audioUrl = audioUrl;
        }
    }

    public static boolean isYoutubeUrl(String s) {
        return YOUTUBE_URL.matcher(s).matches();
    }

    public static Resolved resolve(String url) throws IOException {
        String exe = System.getenv("YTDLP_PATH");
        if (exe == null || exe.isEmpty()) {
            exe = "yt-dlp";
        }

        Process process;
        try {
            process = new ProcessBuilder(Arrays.asList(exe,
                    "-f", "bestaudio[acodec=opus]/bestaudio",
                    "--no-playlist", "--no-warnings",
                    "--print", "title", "--print", "urls",
                    url)).start();
        } catch (IOException e) {
            throw new IOException("yt-dlp is not installed or not on PATH (" + e.getMessage() + ")", e);
        }

        // read both pipes on their own threads so the timeout below works even if yt-dlp hangs
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread stdoutReader = startReader(process.getInputStream(), stdout, "yt-dlp-stdout");
        Thread stderrReader = startReader(process.getErrorStream(), stderr, "yt-dlp-stderr");

        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("yt-dlp took longer than " + TIMEOUT_SECONDS + " seconds");
            }
            stdoutReader.join(1000);
            stderrReader.join(1000);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for yt-dlp", e);
        }

        List<String> lines = new ArrayList<>();
        try (BufferedReader out = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(stdout.toByteArray()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lines.add(line.trim());
                }
            }
        }

        if (process.exitValue() != 0 || lines.size() < 2) {
            String err = new String(stderr.toByteArray(), StandardCharsets.UTF_8).trim();
            throw new IOException("yt-dlp failed: " + (err.isEmpty() ? "no output" : err));
        }
        return new Resolved(lines.get(0), lines.get(1));
    }

    private static Thread startReader(InputStream in, ByteArrayOutputStream out, String name) {
        Thread t = new Thread(() -> copy(in, out), name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        byte[] buf = new byte[4096];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (IOException ignored) {
            // process ended; whatever was read is enough for the error message
        }
    }
}
