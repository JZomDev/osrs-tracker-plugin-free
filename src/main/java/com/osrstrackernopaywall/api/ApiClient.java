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
package com.osrstrackernopaywall.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Handles local event persistence for OSRS Tracker.
 */
@Slf4j
@Singleton
public class ApiClient
{
    private final Gson gson;
    private final ScreenshotLocalService screenshotLocalService;

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    @Inject
    public ApiClient(Gson gson, ScreenshotLocalService screenshotLocalService)
    {
        this.gson = gson;
        this.screenshotLocalService = screenshotLocalService;
    }

    /**
     * Saves a JSON payload locally for the specified endpoint.
     *
     * @param endpoint Event endpoint key (e.g., "/api/webhooks/level_up")
     * @param jsonPayload The JSON string to send in the request body
     * @param eventDescription A human-readable description of the event for logging purposes
     */
    public void sendEventToApi(String endpoint, String jsonPayload, String eventDescription)
    {
        sendEventToApi(endpoint, jsonPayload, eventDescription, null, null);
    }

    /**
     * Saves a JSON payload locally with optional screenshot and video.
     *
     * @param endpoint Event endpoint key (e.g., "/api/webhooks/level_up")
     * @param jsonPayload The JSON string to send in the request body
     * @param eventDescription A human-readable description of the event for logging purposes
     * @param screenshotBase64 Base64-encoded PNG screenshot (optional, can be null)
     * @param videoBase64 Base64-encoded MP4 video (optional, can be null)
     */
    public void sendEventToApi(String endpoint, String jsonPayload, String eventDescription, String screenshotBase64, String videoBase64)
    {
        try
        {
            String eventType = endpointToEventType(endpoint);
            String timestamp = LocalDateTime.now().format(FILE_TS);

            String baseName = eventType + "_" + timestamp;
            JsonObject metadata = parsePayload(jsonPayload);
            metadata.addProperty("event_type", eventType);
            metadata.addProperty("source_endpoint", endpoint);
            metadata.addProperty("saved_at", LocalDateTime.now().toString());

			Path baseDir = RuneLite.RUNELITE_DIR.toPath().resolve("videos").resolve(metadata.get("playername").getAsString()).resolve(eventType);
			Files.createDirectories(baseDir);

			if (screenshotBase64 != null && !screenshotBase64.isEmpty())
            {
                byte[] screenshotBytes = screenshotLocalService.decodeBase64Screenshot(screenshotBase64);
                if (screenshotBytes != null)
                {
                    Path screenshotPath = baseDir.resolve(baseName + ".png");
                    if (screenshotLocalService.saveScreenshot(screenshotBytes, screenshotPath))
                    {
                        metadata.addProperty("screenshot_file", screenshotPath.getFileName().toString());
                    }
                }
            }

            if (videoBase64 != null && !videoBase64.isEmpty())
            {
                byte[] decodedVideo = tryDecodeBase64(videoBase64);
                if (decodedVideo != null)
                {
                    Path videoPath = baseDir.resolve(baseName + ".avi");
                    Files.write(videoPath, decodedVideo);
                    metadata.addProperty("video_file", videoPath.getFileName().toString());
                }
                else
                {
                    metadata.addProperty("video_reference", videoBase64);
                }
            }

            Path metadataPath = baseDir.resolve(baseName + ".json");
            Files.write(metadataPath, gson.toJson(metadata).getBytes(StandardCharsets.UTF_8));
            log.debug("Saved {} locally: {}", eventDescription, metadataPath);
        }
        catch (Exception e)
        {
            log.error("Failed to save {} locally", eventDescription, e);
        }
    }

    /**
     * Always true in local-only mode.
     */
    public boolean isConfigurationValid()
    {
        return true;
    }

    private JsonObject parsePayload(String jsonPayload)
    {
        try
        {
            JsonElement element = gson.fromJson(jsonPayload, JsonElement.class);
            if (element != null && element.isJsonObject())
            {
                return element.getAsJsonObject();
            }
        }
        catch (Exception ignored)
        {
        }

        JsonObject fallback = new JsonObject();
        fallback.addProperty("raw_payload", jsonPayload != null ? jsonPayload : "");
        return fallback;
    }

    private String endpointToEventType(String endpoint)
    {
        if (endpoint == null || endpoint.isEmpty())
        {
            return "event";
        }

        int idx = endpoint.lastIndexOf('/');
        String value = idx >= 0 ? endpoint.substring(idx + 1) : endpoint;
        value = value.replaceAll("[^a-zA-Z0-9_\\-]", "_").toLowerCase();
        return value.isEmpty() ? "event" : value;
    }

    private byte[] tryDecodeBase64(String raw)
    {
        try
        {
            return Base64.getDecoder().decode(raw);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }
}
