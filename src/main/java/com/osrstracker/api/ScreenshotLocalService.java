/*
 * Copyright (c) 2025, Dennis De Vulder
 * All rights reserved.
 */
package com.osrstracker.api;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Local utility service for screenshot decoding and persistence.
 */
@Slf4j
@Singleton
public class ScreenshotLocalService
{
    @Inject
    public ScreenshotLocalService()
    {
    }

    /**
     * Writes screenshot bytes to the target file path.
     */
    public boolean saveScreenshot(byte[] screenshotBytes, Path outputPath)
    {
        if (screenshotBytes == null || screenshotBytes.length == 0)
        {
            log.debug("No screenshot bytes to save");
            return false;
        }
        if (outputPath == null)
        {
            log.warn("Output path is null, cannot save screenshot");
            return false;
        }

        try
        {
            Path parent = outputPath.getParent();
            if (parent != null)
            {
                Files.createDirectories(parent);
            }
            Files.write(outputPath, screenshotBytes);
            return true;
        }
        catch (Exception e)
        {
            log.error("Failed to save screenshot", e);
            return false;
        }
    }

    /**
     * Decodes a base64 screenshot string to raw bytes.
     */
    public byte[] decodeBase64Screenshot(String base64Screenshot)
    {
        if (base64Screenshot == null || base64Screenshot.isEmpty())
        {
            return null;
        }

        try
        {
            String base64Data = base64Screenshot;
            if (base64Screenshot.contains(","))
            {
                base64Data = base64Screenshot.substring(base64Screenshot.indexOf(",") + 1);
            }

            return java.util.Base64.getDecoder().decode(base64Data);
        }
        catch (Exception e)
        {
            log.error("Failed to decode base64 screenshot", e);
            return null;
        }
    }
}

