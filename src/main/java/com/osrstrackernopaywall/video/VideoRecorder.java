/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.osrstrackernopaywall.video;

import lombok.extern.slf4j.Slf4j;
import com.osrstrackernopaywall.OsrsTrackerConfig;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.ImageUtil;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records gameplay video using an in-memory circular buffer of JPEG frames.
 *
 * Implementation details:
 * - Maintains a rolling 10-second buffer of JPEG-compressed frames in memory
 * - Memory footprint is bounded and predictable (~15-30 MB depending on quality)
 * - On clip request, snapshots the last N frames and serializes them for local persistence
 * - Old frames are automatically overwritten (circular buffer)
 * - No disk I/O for normal recording - only memory operations
 *
 * Memory Layout:
 * - MAX_FRAMES = 300 frames (10 seconds at 30 FPS)
 * - Average JPEG size ~50KB = ~15MB total buffer
 * - Only JPEG bytes stored, raw frames discarded immediately
 */
@Slf4j
@Singleton
public class VideoRecorder
{
    private final DrawManager drawManager;
    private final OsrsTrackerConfig config;
    private final Client client;
    private final ChatMessageManager chatMessageManager;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService asyncWriter;
    private final ExecutorService apiExecutor;

    // Circular buffer configuration
    private static final int MAX_FRAMES = 300; // 10 seconds at 30 FPS

    // Circular buffer storage
    private final byte[][] jpegBuffer = new byte[MAX_FRAMES][];
    private final long[] timestampBuffer = new long[MAX_FRAMES];
    private final boolean[] needsBlurBuffer = new boolean[MAX_FRAMES]; // Tracks if frame needs blur (deferred to clip finalization)
    private final AtomicInteger writeIndex = new AtomicInteger(0);
    private final AtomicInteger frameCount = new AtomicInteger(0);
    private final Object bufferLock = new Object();

    // Recording state
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isCapturingPostEvent = new AtomicBoolean(false);

    // Backpressure: limit concurrent encoding operations
    private final AtomicInteger pendingEncodes = new AtomicInteger(0);
    private static final int MAX_PENDING_ENCODES = 4;

    // Sensitive content detection - cached to avoid expensive widget lookups every frame
    private final AtomicBoolean sensitiveContentVisible = new AtomicBoolean(false);
    private final AtomicLong lastSensitiveCheck = new AtomicLong(0);
    private static final long SENSITIVE_CHECK_INTERVAL_MS = 500; // Check every 500ms instead of every frame

    private AsyncFrameCapture asyncFrameCapture;

    // Track current capture settings to detect quality changes
    private volatile int currentCaptureFps = 0;
    private volatile float currentJpegQuality = 0.5f;

    // Set when GPU plugin is detected as unavailable - prevents retry loop in updateCaptureRateIfNeeded.
    // Reset when the plugin restarts (startUp calls startRecording which resets this).
    private volatile boolean gpuUnavailable = false;
    private volatile boolean gpuWarningShown = false;

    // Blur kernel for sensitive content protection (15x15 box blur for heavy blur)
    private static final int BLUR_RADIUS = 15;

    @Inject
    public VideoRecorder(DrawManager drawManager, OsrsTrackerConfig config, Client client, ChatMessageManager chatMessageManager)
    {
        this.drawManager = drawManager;
        this.config = config;
        this.client = client;
        this.chatMessageManager = chatMessageManager;

        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "OSRS-Tracker-Video-Scheduler");
            t.setDaemon(true);
            return t;
        });
        // Use 2 writer threads to limit memory pressure
        this.asyncWriter = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "OSRS-Tracker-Video-Writer");
            t.setDaemon(true);
            return t;
        });

        // Dedicated single thread for heavier clip serialization work
        this.apiExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OSRS-Tracker-API");
            t.setDaemon(true);
            return t;
        });

        log.debug("Video recorder initialized ({} frames max)", MAX_FRAMES);
    }

    // ---- Package-private API for AsyncFrameCapture ----

    /**
     * @return true if recording is currently active
     */
    boolean isCurrentlyRecording()
    {
        return isRecording.get();
    }

    /**
     * @return the current capture FPS setting, or 0 if not recording
     */
    int getCaptureFps()
    {
        return currentCaptureFps;
    }

    /**
     * @return true if the encoding pipeline can accept another frame (backpressure check)
     */
    boolean canAcceptFrame()
    {
        return pendingEncodes.get() < MAX_PENDING_ENCODES;
    }

    /**
     * Submits a raw RGBA frame from PBO readback for async encoding and storage.
     * Handles blur detection, RGBA-to-RGB conversion, JPEG encoding, and circular buffer storage.
     * This method returns immediately; encoding happens on a background thread.
     *
     * @param rgbaPixels Raw RGBA pixel data from PBO (bottom-up, OpenGL convention)
     * @param width The width of the image in pixels
     * @param height The height of the image in pixels
     */
    void submitCapturedFrame(ByteBuffer rgbaPixels, int width, int height)
    {
        long currentTimeMs = System.currentTimeMillis();
        boolean shouldBlur = getCachedSensitiveContentVisible(currentTimeMs);

        pendingEncodes.incrementAndGet();
        asyncWriter.submit(() -> {
            try
            {
                encodeAndStoreFrame(rgbaPixels, width, height, currentTimeMs, shouldBlur);
            }
            catch (Exception e)
            {
                log.error("Failed to encode PBO frame", e);
            }
            finally
            {
                pendingEncodes.decrementAndGet();
            }
        });
    }

    /**
     * Checks if any sensitive content is currently visible that should be blurred.
     * This includes:
     * - Login screen (protects username/email input)
     * - Login screen authenticator (protects 2FA codes)
     * - Logging in state (transitional state during login)
     * - Bank PIN entry interface
     * - Bank PIN settings interface
     *
     * @return true if sensitive content is visible
     */
    private boolean isSensitiveContentVisible()
    {
        try
        {
            // Check for login screen states - blur any screen where user might enter credentials
            GameState gameState = client.getGameState();
            if (gameState == GameState.LOGIN_SCREEN ||
                gameState == GameState.LOGIN_SCREEN_AUTHENTICATOR ||
                gameState == GameState.LOGGING_IN)
            {
                return true;
            }

            // Check for Bank PIN interface (InterfaceID.BANKPIN_KEYPAD = 213)
            Widget bankPinWidget = client.getWidget(InterfaceID.BANKPIN_KEYPAD, 0);
            if (bankPinWidget != null && !bankPinWidget.isHidden())
            {
                return true;
            }

            // Check for Bank PIN settings interface (InterfaceID.BANKPIN_SETTINGS = 14)
            Widget bankPinSettingsWidget = client.getWidget(InterfaceID.BANKPIN_SETTINGS, 0);
            if (bankPinSettingsWidget != null && !bankPinSettingsWidget.isHidden())
            {
                return true;
            }
        }
        catch (Exception e)
        {
            // Silently ignore widget access errors
            log.debug("Error checking for sensitive content: {}", e.getMessage());
        }

        return false;
    }

    /**
     * Gets the cached sensitive content visibility status.
     * Only performs the expensive widget lookup every SENSITIVE_CHECK_INTERVAL_MS milliseconds.
     * This reduces overhead from 30 widget lookups/second to ~2/second.
     *
     * @param currentTime The current timestamp
     * @return true if sensitive content is visible (cached result)
     */
    private boolean getCachedSensitiveContentVisible(long currentTime)
    {
        long lastCheck = lastSensitiveCheck.get();

        // If enough time has passed, update the cached value
        if (currentTime - lastCheck >= SENSITIVE_CHECK_INTERVAL_MS)
        {
            if (lastSensitiveCheck.compareAndSet(lastCheck, currentTime))
            {
                boolean isVisible = isSensitiveContentVisible();
                sensitiveContentVisible.set(isVisible);
                return isVisible;
            }
        }

        // Return cached value
        return sensitiveContentVisible.get();
    }

    /**
     * Applies a heavy box blur to the image to obscure sensitive content.
     * Uses multiple passes of a box blur for a strong effect that makes
     * the content unreadable while still showing something happened.
     *
     * @param image The image to blur
     * @return A heavily blurred version of the image
     */
    private BufferedImage applyHeavyBlur(BufferedImage image)
    {
        int width = image.getWidth();
        int height = image.getHeight();

        // Create a copy to work with - use TYPE_INT_RGB for proper color handling
        BufferedImage blurred = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        // Copy original to blurred
        java.awt.Graphics2D g = blurred.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();

        // Apply multiple passes of box blur for heavy blur effect
        for (int pass = 0; pass < 3; pass++)
        {
            blurred = boxBlur(blurred, BLUR_RADIUS);
        }

        // Add a semi-transparent overlay to further obscure
        g = blurred.createGraphics();
        g.setColor(new java.awt.Color(0, 0, 0, 100)); // Semi-transparent black
        g.fillRect(0, 0, width, height);

        // Add warning text
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 24));
        String warningText = "SENSITIVE CONTENT HIDDEN";
        java.awt.FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(warningText);
        int textX = (width - textWidth) / 2;
        int textY = height / 2;
        g.drawString(warningText, textX, textY);
        g.dispose();

        return blurred;
    }

    /**
     * Simple box blur implementation.
     */
    private BufferedImage boxBlur(BufferedImage image, int radius)
    {
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        int[] pixels = new int[width * height];
        int[] result = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);

        // Horizontal pass
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                int r = 0, g = 0, b = 0, count = 0;

                for (int kx = -radius; kx <= radius; kx++)
                {
                    int px = Math.max(0, Math.min(width - 1, x + kx));
                    int pixel = pixels[y * width + px];
                    r += (pixel >> 16) & 0xFF;
                    g += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                    count++;
                }

                result[y * width + x] = (0xFF << 24) | ((r / count) << 16) | ((g / count) << 8) | (b / count);
            }
        }

        // Vertical pass
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                int r = 0, g = 0, b = 0, count = 0;

                for (int ky = -radius; ky <= radius; ky++)
                {
                    int py = Math.max(0, Math.min(height - 1, y + ky));
                    int pixel = result[py * width + x];
                    r += (pixel >> 16) & 0xFF;
                    g += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                    count++;
                }

                pixels[y * width + x] = (0xFF << 24) | ((r / count) << 16) | ((g / count) << 8) | (b / count);
            }
        }

        output.setRGB(0, 0, width, height, pixels, 0, width);
        return output;
    }

    /**
     * Starts continuous recording with an in-memory circular buffer.
     */
    public void startRecording()
    {
        if (isRecording.getAndSet(true))
        {
            return;
        }

        // Clear the buffer
        synchronized (bufferLock)
        {
            for (int i = 0; i < MAX_FRAMES; i++)
            {
                jpegBuffer[i] = null;
                timestampBuffer[i] = 0;
            }
            writeIndex.set(0);
            frameCount.set(0);
        }

        gpuUnavailable = false;
        gpuWarningShown = false;

        // Capture at the quality setting's FPS and JPEG quality
        VideoQuality quality = config.videoQuality();
        currentCaptureFps = quality.getFps() > 0 ? quality.getFps() : 30;
        currentJpegQuality = quality.getJpegQuality() > 0 ? quality.getJpegQuality() : 0.5f;
        log.debug("Video capture started at {} FPS, {}% JPEG quality", currentCaptureFps, (int)(currentJpegQuality * 100));

        // Use async PBO capture via everyFrameListener for near-zero client thread stall.
        // If GPU plugin is not active, onGpuUnavailable stops recording and falls back to screenshot-only.
        asyncFrameCapture = new AsyncFrameCapture(drawManager, this, this::onGpuUnavailable);
        asyncFrameCapture.start();
    }

    /**
     * Stops recording and cleans up resources.
     */
    public void stopRecording()
    {
        if (!isRecording.getAndSet(false))
        {
            return;
        }

        if (asyncFrameCapture != null)
        {
            asyncFrameCapture.stop();
            asyncFrameCapture = null;
        }

        // Clear the buffer to free memory
        synchronized (bufferLock)
        {
            for (int i = 0; i < MAX_FRAMES; i++)
            {
                jpegBuffer[i] = null;
                timestampBuffer[i] = 0;
                needsBlurBuffer[i] = false;
            }
            writeIndex.set(0);
            frameCount.set(0);
        }

        currentCaptureFps = 0;
    }

    /**
     * Called by AsyncFrameCapture when no GL context is available (GPU plugin disabled).
     * Stops video recording entirely - GPU plugin is required for video capture.
     * Events will fall back to screenshot-only mode automatically since isRecording is false.
     * Chat notification is deferred to captureEventVideo since this fires before login.
     */
    private void onGpuUnavailable()
    {
        log.debug("GPU plugin not active, falling back to screenshot-only");

        // Clean up the async frame capture
        if (asyncFrameCapture != null)
        {
            asyncFrameCapture.stop();
            asyncFrameCapture = null;
        }

        // Stop recording - captureEventVideo will fall back to screenshot-only since isRecording is false
        isRecording.set(false);
        currentCaptureFps = 0;
        gpuUnavailable = true;
    }

    /**
     * Updates the capture settings if the quality setting has changed.
     * Call this periodically or when quality settings change.
     */
    public void updateCaptureRateIfNeeded()
    {
        if (isCapturingPostEvent.get())
        {
            return;
        }

        VideoQuality quality = config.videoQuality();

        // Handle Screenshot Only mode - stop all frame capture
        if (quality == VideoQuality.SCREENSHOT_ONLY)
        {
            if (currentCaptureFps != 0)
            {
                log.debug("Switching to Screenshot Only mode");

                // Stop async PBO capture
                if (asyncFrameCapture != null)
                {
                    asyncFrameCapture.stop();
                    asyncFrameCapture = null;
                }

                // Clear the buffer to free memory
                synchronized (bufferLock)
                {
                    for (int i = 0; i < MAX_FRAMES; i++)
                    {
                        jpegBuffer[i] = null;
                        timestampBuffer[i] = 0;
                        needsBlurBuffer[i] = false;
                    }
                    writeIndex.set(0);
                    frameCount.set(0);
                }

                currentCaptureFps = 0;
                currentJpegQuality = 0;
            }
            return;
        }

        // Video mode - but GPU plugin is required for recording
        if (gpuUnavailable)
        {
            return;
        }

        int targetFps = quality.getFps();
        float targetJpegQuality = quality.getJpegQuality();

        // Update JPEG quality if changed (takes effect on next frame)
        if (targetJpegQuality != currentJpegQuality)
        {
            currentJpegQuality = targetJpegQuality;
        }

        // Update FPS if changed (AsyncFrameCapture reads currentCaptureFps directly each frame)
        if (targetFps != currentCaptureFps)
        {
            log.debug("Capture rate changed from {} to {} FPS", currentCaptureFps, targetFps);

            // Clear buffer when switching quality to avoid mixing frame rates
            synchronized (bufferLock)
            {
                for (int i = 0; i < MAX_FRAMES; i++)
                {
                    jpegBuffer[i] = null;
                    timestampBuffer[i] = 0;
                    needsBlurBuffer[i] = false;
                }
                writeIndex.set(0);
                frameCount.set(0);
            }

            // Update FPS - AsyncFrameCapture reads this volatile field directly
            currentCaptureFps = targetFps;

            // Start async capture if not already running (switching from Screenshot Only)
            if (asyncFrameCapture == null)
            {
                asyncFrameCapture = new AsyncFrameCapture(drawManager, this, this::onGpuUnavailable);
                asyncFrameCapture.start();
            }

            // Mark as recording if we weren't before
            isRecording.set(true);
        }
    }

    // Default post-event duration in milliseconds
    // 4 seconds post-event gives time for interfaces/celebrations to appear
    // (6 seconds pre-event + 4 seconds post-event = 10 seconds total)
    private static final int DEFAULT_POST_EVENT_MS = 4000;

    /**
     * Triggers a video/screenshot capture for an event based on configured quality settings.
     * Uses default 4-second post-event duration to capture celebration animations.
     *
     * @param callback Called when capture is complete with base64-encoded screenshot and optionally MP4 video
     */
    public void captureEventVideo(VideoCallback callback)
    {
        captureEventVideo(callback, null, DEFAULT_POST_EVENT_MS);
    }

    /**
     * Triggers a video/screenshot capture for an event based on configured quality settings.
     * Uses default 4-second post-event duration to capture celebration animations.
     *
     * @param callback Called when capture is complete with base64-encoded screenshot and optionally MP4 video
     * @param onEncodingStart Optional callback fired when encoding begins (after recording stops)
     */
    public void captureEventVideo(VideoCallback callback, Runnable onEncodingStart)
    {
        captureEventVideo(callback, onEncodingStart, DEFAULT_POST_EVENT_MS);
    }

    /**
     * Triggers a video/screenshot capture for an event based on configured quality settings.
     *
     * @param callback Called when capture is complete with base64-encoded screenshot and optionally MP4 video
     * @param onEncodingStart Optional callback fired when encoding begins (after recording stops)
     * @param postEventMs Duration in milliseconds to continue recording after the event (default 4000ms)
     */
    public void captureEventVideo(VideoCallback callback, Runnable onEncodingStart, int postEventMs)
    {
        if (!isRecording.get())
        {
            // Show one-time chat notification when GPU is unavailable
            if (gpuUnavailable && !gpuWarningShown)
            {
                gpuWarningShown = true;
                if (chatMessageManager != null)
                {
                    chatMessageManager.queue(QueuedMessage.builder()
                        .type(ChatMessageType.GAMEMESSAGE)
                        .runeLiteFormattedMessage("<col=ff9040>[OSRS Tracker] GPU plugin required for video. Using screenshot-only mode.</col>")
                        .build());
                }
            }

            if (onEncodingStart != null)
            {
                onEncodingStart.run();
            }
            captureScreenshotOnly(callback);
            return;
        }

        // Guard against overlapping captures - fall back to screenshot if already capturing
        if (isCapturingPostEvent.get())
        {
            if (onEncodingStart != null)
            {
                onEncodingStart.run();
            }
            captureScreenshotOnly(callback);
            return;
        }

        VideoQuality quality = config.videoQuality();

        // Check if screenshot-only mode
        if (quality == VideoQuality.SCREENSHOT_ONLY)
        {
            if (onEncodingStart != null)
            {
                onEncodingStart.run();
            }
            captureScreenshotOnly(callback);
            return;
        }

        // Video capture mode
        int durationMs = quality.getDurationMs();
        int bufferMs = durationMs - postEventMs;

        // Ensure buffer is at least 1 second
        if (bufferMs < 1000)
        {
            bufferMs = 1000;
        }

        isCapturingPostEvent.set(true);

        // Calculate the time window for the video
        final long captureStartTime = System.currentTimeMillis();
        final long videoStartTime = captureStartTime - bufferMs;
        final long videoEndTime = captureStartTime + postEventMs;
        final int finalPostEventMs = postEventMs;

        // Schedule task to finalize capture after post-event duration
        scheduler.schedule(() -> {
            isCapturingPostEvent.set(false);
            // Notify caller that encoding is about to start
            if (onEncodingStart != null)
            {
                onEncodingStart.run();
            }
            finalizeCapture(callback, videoStartTime, videoEndTime);
        }, finalPostEventMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Captures a single screenshot without video.
     * Useful for events where only a screenshot is needed (e.g., clue scrolls).
     * Applies blur if sensitive content (like bank PIN) is visible.
     *
     * @param callback Called when screenshot capture is complete
     */
    public void captureScreenshotOnly(VideoCallback callback)
    {
        // Check for sensitive content on the client thread
        final boolean shouldBlur = isSensitiveContentVisible();

        drawManager.requestNextFrameListener(image -> {
            asyncWriter.submit(() -> {
                try
                {
                    BufferedImage screenshot = ImageUtil.bufferedImageFromImage(image);

                    // Apply blur if sensitive content was detected
                    if (shouldBlur)
                    {
                        screenshot = applyHeavyBlur(screenshot);
                    }

                    String screenshotBase64 = imageToBase64(screenshot);
                    callback.onComplete(screenshotBase64, null);
                }
                catch (Exception e)
                {
                    log.error("Failed to capture screenshot", e);
                    callback.onComplete(null, null);
                }
            });
        });
    }

    // Maximum resolution cap (1080p) to normalize saved clip sizes across different displays
    private static final int MAX_WIDTH = 1920;
    private static final int MAX_HEIGHT = 1080;

    /**
     * Encodes a captured Image to JPEG and stores it in the circular buffer.
     * Caps resolution at 1080p to normalize file sizes across different displays.
     * Blur is deferred to finalize phase to save CPU (most frames are overwritten before clip extraction).
     *
     * @param image The image to encode
     * @param timestamp The timestamp for the frame
     * @param shouldBlur Whether to mark this frame for deferred blur
     */
    private void encodeAndStoreFrame(Image image, long timestamp, boolean shouldBlur)
    {
        try
        {
            if (image == null)
            {
                return;
            }

            int sourceWidth = image.getWidth(null);
            int sourceHeight = image.getHeight(null);
            if (sourceWidth <= 0 || sourceHeight <= 0)
            {
                return;
            }

            BufferedImage bufferedImage = scaleToMaxResolution(image, sourceWidth, sourceHeight);
            byte[] jpegBytes = encodeToJpeg(bufferedImage);
            storeInBuffer(jpegBytes, timestamp, shouldBlur);
        }
        catch (IOException e)
        {
            log.error("Failed to encode frame at timestamp {}", timestamp, e);
        }
    }

    /**
     * Encodes a raw RGBA ByteBuffer from PBO readback into JPEG and stores in the circular buffer.
     * Performs RGBA-to-RGB conversion with vertical flip (OpenGL origin is bottom-left).
     *
     * @param rgbaPixels Raw RGBA pixel data from PBO (bottom-up, OpenGL convention)
     * @param width The width of the image in pixels
     * @param height The height of the image in pixels
     * @param timestamp The timestamp for the frame
     * @param shouldBlur Whether to mark this frame for deferred blur
     */
    private void encodeAndStoreFrame(ByteBuffer rgbaPixels, int width, int height, long timestamp, boolean shouldBlur)
    {
        try
        {
            if (rgbaPixels == null || width <= 0 || height <= 0)
            {
                return;
            }

            BufferedImage bufferedImage = convertRgbaToBufferedImage(rgbaPixels, width, height);
            bufferedImage = scaleToMaxResolution(bufferedImage, width, height);
            byte[] jpegBytes = encodeToJpeg(bufferedImage);
            storeInBuffer(jpegBytes, timestamp, shouldBlur);
        }
        catch (IOException e)
        {
            log.error("Failed to encode PBO frame at timestamp {}", timestamp, e);
        }
    }

    /**
     * Converts raw RGBA pixels (bottom-up, OpenGL convention) to a BufferedImage with vertical flip.
     */
    private BufferedImage convertRgbaToBufferedImage(ByteBuffer rgbaPixels, int width, int height)
    {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        rgbaPixels.rewind();
        int rowBytes = width * 4;

        for (int y = 0; y < height; y++)
        {
            int srcRow = (height - 1 - y) * rowBytes;
            for (int x = 0; x < width; x++)
            {
                int srcIdx = srcRow + x * 4;
                int r = rgbaPixels.get(srcIdx) & 0xFF;
                int g = rgbaPixels.get(srcIdx + 1) & 0xFF;
                int b = rgbaPixels.get(srcIdx + 2) & 0xFF;
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }

        return image;
    }

    /**
     * Scales an image down to MAX_WIDTH x MAX_HEIGHT if it exceeds those dimensions.
     * Maintains aspect ratio using bilinear interpolation.
     *
     * @param source The source image
     * @param sourceWidth The source width
     * @param sourceHeight The source height
     * @return A BufferedImage at target resolution (may be the same as input if no scaling needed)
     */
    private BufferedImage scaleToMaxResolution(Image source, int sourceWidth, int sourceHeight)
    {
        int targetWidth = sourceWidth;
        int targetHeight = sourceHeight;

        if (sourceWidth > MAX_WIDTH || sourceHeight > MAX_HEIGHT)
        {
            double scaleFactor = Math.min(
                (double) MAX_WIDTH / sourceWidth,
                (double) MAX_HEIGHT / sourceHeight
            );
            targetWidth = (int) (sourceWidth * scaleFactor);
            targetHeight = (int) (sourceHeight * scaleFactor);
        }

        BufferedImage result = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = result.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return result;
    }

    /**
     * Encodes a BufferedImage to JPEG bytes using the current quality setting.
     *
     * @param image The image to encode
     * @return JPEG-encoded bytes
     * @throws IOException if encoding fails
     */
    private byte[] encodeToJpeg(BufferedImage image) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(30000);

        javax.imageio.ImageWriter jpegWriter = ImageIO.getImageWritersByFormatName("jpg").next();
        javax.imageio.ImageWriteParam param = jpegWriter.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(currentJpegQuality);

        javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
        jpegWriter.setOutput(ios);
        jpegWriter.write(null, new javax.imageio.IIOImage(image, null, null), param);
        jpegWriter.dispose();
        ios.close();

        return baos.toByteArray();
    }

    /**
     * Stores JPEG bytes in the circular buffer with a timestamp and blur flag.
     * Blur is deferred to finalize phase to save CPU since most frames are overwritten before extraction.
     *
     * @param jpegBytes The JPEG-encoded frame data
     * @param timestamp The timestamp for the frame
     * @param shouldBlur Whether this frame needs blur applied during clip finalization
     */
    private void storeInBuffer(byte[] jpegBytes, long timestamp, boolean shouldBlur)
    {
        synchronized (bufferLock)
        {
            int idx = writeIndex.getAndIncrement() % MAX_FRAMES;
            jpegBuffer[idx] = jpegBytes;
            timestampBuffer[idx] = timestamp;
            needsBlurBuffer[idx] = shouldBlur;

            if (frameCount.get() < MAX_FRAMES)
            {
                frameCount.incrementAndGet();
            }
        }
    }

    /**
     * Finalizes the video capture by taking a screenshot and serializing buffered frames.
     *
     * @param callback The callback to invoke when complete
     * @param videoStartTime The start timestamp for the video (frames before this are excluded)
     * @param videoEndTime The end timestamp for the video (frames after this are excluded)
     */
    private void finalizeCapture(VideoCallback callback, long videoStartTime, long videoEndTime)
    {
        // Check for sensitive content before capturing final screenshot
        final boolean shouldBlur = isSensitiveContentVisible();

        // Capture one more frame as the screenshot
        drawManager.requestNextFrameListener(image -> {
            asyncWriter.submit(() -> {
                try
                {
                    // Convert screenshot to base64 PNG
                    BufferedImage screenshot = ImageUtil.bufferedImageFromImage(image);

                    if (shouldBlur)
                    {
                        screenshot = applyHeavyBlur(screenshot);
                    }

                    String screenshotBase64 = imageToBase64(screenshot);

                    // Prepare frames snapshot (fast, on asyncWriter thread)
                    FrameSnapshot frameSnapshot = prepareFrameSnapshot(videoStartTime, videoEndTime);

                    if (frameSnapshot == null || frameSnapshot.frames.isEmpty())
                    {
                        callback.onComplete(screenshotBase64, null);
                        return;
                    }

                    apiExecutor.submit(() -> {
                        try
                        {
                            String videoBase64 = encodeFramesToBase64(frameSnapshot.frames);
                            callback.onComplete(screenshotBase64, videoBase64);
                        }
                        catch (Exception e)
                        {
                            log.error("Failed to serialize video clip", e);
                            callback.onComplete(screenshotBase64, null);
                        }
                    });
                }
                catch (Exception e)
                {
                    log.error("Failed to finalize capture", e);
                    callback.onComplete(null, null);
                }
            });
        });
    }

    /**
     * Container for frame snapshot data.
     */
    private static class FrameSnapshot
    {
        List<byte[]> frames;
    }

    /**
     * Prepares a frame snapshot by extracting frames from the circular buffer.
     * Applies deferred blur to frames that need it.
     * This is fast and runs on the asyncWriter thread.
     *
     * @param videoStartTime The start timestamp for the video
     * @param videoEndTime The end timestamp for the video
     * @return FrameSnapshot with prepared frames, or null if no frames available
     */
    private FrameSnapshot prepareFrameSnapshot(long videoStartTime, long videoEndTime)
    {
        // First, snapshot frame data from circular buffer (minimal locked time)
        // We copy references to frame data under lock, then process outside lock
        List<FrameData> framesToProcess = new ArrayList<>();

        synchronized (bufferLock)
        {
            int count = frameCount.get();
            int currentWriteIdx = writeIndex.get() % MAX_FRAMES;

            for (int i = 0; i < count; i++)
            {
                int idx = (currentWriteIdx - count + i + MAX_FRAMES) % MAX_FRAMES;
                long timestamp = timestampBuffer[idx];
                byte[] frame = jpegBuffer[idx];
                boolean needsBlur = needsBlurBuffer[idx];

                if (timestamp >= videoStartTime && timestamp <= videoEndTime && frame != null)
                {
                    // Copy frame data for processing outside lock
                    framesToProcess.add(new FrameData(frame, needsBlur));
                }
            }
        }

        // Process frames outside the lock (apply blur if needed)
        // This prevents holding the bufferLock during expensive ImageIO operations
        List<byte[]> clipFrames = new ArrayList<>();
        int blurAppliedCount = 0;

        for (FrameData frameData : framesToProcess)
        {
            byte[] frame = frameData.frame;

            // Apply deferred blur if needed (only for frames included in the final clip)
            if (frameData.needsBlur)
            {
                try
                {
                    BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(frame));
                    if (decoded != null)
                    {
                        BufferedImage blurred = applyHeavyBlur(decoded);
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        ImageIO.write(blurred, "jpg", baos);
                        frame = baos.toByteArray();
                        blurAppliedCount++;
                    }
                }
                catch (IOException e)
                {
                    log.error("Failed to apply deferred blur to frame", e);
                    // Use original frame if blur fails
                }
            }
            clipFrames.add(frame);
        }

        if (clipFrames.isEmpty())
        {
            return null;
        }

        FrameSnapshot snapshot = new FrameSnapshot();
        snapshot.frames = clipFrames;
        return snapshot;
    }

    /**
     * Simple data class to hold frame data for processing outside synchronized block.
     */
    private static class FrameData
    {
        final byte[] frame;
        final boolean needsBlur;

        FrameData(byte[] frame, boolean needsBlur)
        {
            this.frame = frame;
            this.needsBlur = needsBlur;
        }
    }

    /**
     * Serializes captured JPEG frames into a base64-encoded MJPEG AVI file.
     */
    private String encodeFramesToBase64(List<byte[]> frames) throws IOException
    {
        byte[] aviBytes = buildMjpegAvi(frames);
        return Base64.getEncoder().encodeToString(aviBytes);
    }

    /**
     * Wraps JPEG frames in a RIFF AVI container with an MJPEG video stream.
     * Produces a file playable by VLC, Windows Media Player, etc.
     */
    private byte[] buildMjpegAvi(List<byte[]> frames) throws IOException
    {
        if (frames.isEmpty())
        {
            return new byte[0];
        }

        int[] dims = readJpegDimensions(frames.get(0));
        int width = dims[0];
        int height = dims[1];
        int fps = (currentCaptureFps > 0) ? currentCaptureFps : 30;
        int frameCount = frames.size();

        int maxFrameSize = 0;
        for (byte[] f : frames)
        {
            maxFrameSize = Math.max(maxFrameSize, f.length);
        }

        // Build movi data (the actual frame chunks) and record per-frame offsets for idx1.
        // Offsets are measured from the start of the "movi" FourCC (4 bytes before the first chunk).
        ByteArrayOutputStream moviData = new ByteArrayOutputStream();
        int[] frameOffsets = new int[frameCount];
        int moviCursor = 4; // accounts for "movi" FourCC written just before these chunks

        for (int i = 0; i < frameCount; i++)
        {
            byte[] frame = frames.get(i);
            frameOffsets[i] = moviCursor;
            writeFourCC(moviData, "00dc");
            writeInt32LE(moviData, frame.length);
            moviData.write(frame);
            if (frame.length % 2 != 0)
            {
                moviData.write(0); // pad to even boundary
            }
            moviCursor += 8 + frame.length + (frame.length % 2);
        }
        byte[] moviBytes = moviData.toByteArray();

        // Size constants (all chunk sizes are the payload only, not including the 8-byte header).
        // strh: 56 bytes, strf (BITMAPINFOHEADER): 40 bytes, avih: 56 bytes
        // strl LIST body: "strl"(4) + strh-chunk(64) + strf-chunk(48) = 116
        // hdrl LIST body: "hdrl"(4) + avih-chunk(64) + strl-LIST(124)  = 192
        // movi LIST body: "movi"(4) + moviBytes.length
        // idx1 payload:   16 * frameCount
        int idx1Size = 16 * frameCount;
        int riffBodySize = 4                            // "AVI "
            + 8 + 192                                  // hdrl LIST
            + 8 + 4 + moviBytes.length                 // movi LIST
            + 8 + idx1Size;                            // idx1 chunk

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // RIFF header
        writeFourCC(out, "RIFF");
        writeInt32LE(out, riffBodySize);
        writeFourCC(out, "AVI ");

        // hdrl LIST
        writeFourCC(out, "LIST");
        writeInt32LE(out, 192);
        writeFourCC(out, "hdrl");

        // avih (AVI main header)
        writeFourCC(out, "avih");
        writeInt32LE(out, 56);
        writeInt32LE(out, 1000000 / fps);      // dwMicroSecPerFrame
        writeInt32LE(out, maxFrameSize * fps); // dwMaxBytesPerSec
        writeInt32LE(out, 0);                  // dwPaddingGranularity
        writeInt32LE(out, 0x10);               // dwFlags: AVIF_HASINDEX
        writeInt32LE(out, frameCount);         // dwTotalFrames
        writeInt32LE(out, 0);                  // dwInitialFrames
        writeInt32LE(out, 1);                  // dwStreams
        writeInt32LE(out, maxFrameSize);       // dwSuggestedBufferSize
        writeInt32LE(out, width);              // dwWidth
        writeInt32LE(out, height);             // dwHeight
        writeInt32LE(out, 0);                  // dwReserved[0..3]
        writeInt32LE(out, 0);
        writeInt32LE(out, 0);
        writeInt32LE(out, 0);

        // strl LIST
        writeFourCC(out, "LIST");
        writeInt32LE(out, 116); // "strl"(4) + strh-chunk(64) + strf-chunk(48)
        writeFourCC(out, "strl");

        // strh (video stream header)
        writeFourCC(out, "strh");
        writeInt32LE(out, 56);
        writeFourCC(out, "vids");        // fccType
        writeFourCC(out, "MJPG");        // fccHandler
        writeInt32LE(out, 0);            // dwFlags
        writeInt16LE(out, 0);            // wPriority
        writeInt16LE(out, 0);            // wLanguage
        writeInt32LE(out, 0);            // dwInitialFrames
        writeInt32LE(out, 1);            // dwScale
        writeInt32LE(out, fps);          // dwRate
        writeInt32LE(out, 0);            // dwStart
        writeInt32LE(out, frameCount);   // dwLength
        writeInt32LE(out, maxFrameSize); // dwSuggestedBufferSize
        writeInt32LE(out, 0xFFFFFFFF);   // dwQuality (-1 = default)
        writeInt32LE(out, 0);            // dwSampleSize
        writeInt16LE(out, 0);            // rcFrame.left
        writeInt16LE(out, 0);            // rcFrame.top
        writeInt16LE(out, width);        // rcFrame.right
        writeInt16LE(out, height);       // rcFrame.bottom

        // strf (BITMAPINFOHEADER)
        writeFourCC(out, "strf");
        writeInt32LE(out, 40);
        writeInt32LE(out, 40);                 // biSize
        writeInt32LE(out, width);              // biWidth
        writeInt32LE(out, height);             // biHeight
        writeInt16LE(out, 1);                  // biPlanes
        writeInt16LE(out, 24);                 // biBitCount
        writeFourCC(out, "MJPG");              // biCompression
        writeInt32LE(out, width * height * 3); // biSizeImage
        writeInt32LE(out, 0);                  // biXPelsPerMeter
        writeInt32LE(out, 0);                  // biYPelsPerMeter
        writeInt32LE(out, 0);                  // biClrUsed
        writeInt32LE(out, 0);                  // biClrImportant

        // movi LIST
        writeFourCC(out, "LIST");
        writeInt32LE(out, 4 + moviBytes.length);
        writeFourCC(out, "movi");
        out.write(moviBytes);

        // idx1 (legacy AVI index — enables seeking in most players)
        writeFourCC(out, "idx1");
        writeInt32LE(out, idx1Size);
        for (int i = 0; i < frameCount; i++)
        {
            writeFourCC(out, "00dc");
            writeInt32LE(out, 0x10);           // AVIIF_KEYFRAME
            writeInt32LE(out, frameOffsets[i]); // offset from start of movi FourCC
            writeInt32LE(out, frames.get(i).length);
        }

        return out.toByteArray();
    }

    /**
     * Parses width and height from a JPEG SOF marker.
     * Returns {1280, 720} as a fallback if parsing fails.
     */
    private static int[] readJpegDimensions(byte[] jpeg)
    {
        for (int i = 0; i < jpeg.length - 9; i++)
        {
            if ((jpeg[i] & 0xFF) == 0xFF)
            {
                int marker = jpeg[i + 1] & 0xFF;
                // SOF0 (0xC0), SOF1 (0xC1), SOF2 (0xC2) all carry frame dimensions
                if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2)
                {
                    int h = ((jpeg[i + 5] & 0xFF) << 8) | (jpeg[i + 6] & 0xFF);
                    int w = ((jpeg[i + 7] & 0xFF) << 8) | (jpeg[i + 8] & 0xFF);
                    if (w > 0 && h > 0)
                    {
                        return new int[]{w, h};
                    }
                }
            }
        }
        return new int[]{1280, 720};
    }

    private static void writeFourCC(ByteArrayOutputStream out, String fourcc)
    {
        byte[] b = fourcc.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(b, 0, 4);
    }

    private static void writeInt32LE(ByteArrayOutputStream out, int value)
    {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeInt16LE(ByteArrayOutputStream out, int value)
    {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    /**
     * Converts a BufferedImage to a base64-encoded PNG string.
     */
    private String imageToBase64(BufferedImage image) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        byte[] imageBytes = baos.toByteArray();
        return Base64.getEncoder().encodeToString(imageBytes);
    }


    /**
     * Callback interface for video capture completion.
     */
    public interface VideoCallback
    {
        /**
         * Called when video capture is complete.
         *
         * @param screenshotBase64 Base64-encoded PNG screenshot
         * @param videoKey Base64-encoded video bytes, or null if not available
         */
        void onComplete(String screenshotBase64, String videoKey);
    }
}
