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
package com.osrstracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import com.osrstracker.video.VideoQuality;

@ConfigGroup("osrstracker")
public interface OsrsTrackerConfig extends Config
{
    // Minimum loot value (100k GP)
    int MINIMUM_LOOT_VALUE = 100000;

    /**
     * Parses a GP value string that supports k (thousands) and m (millions) suffixes.
     * Examples: "100k" = 100000, "1.5m" = 1500000, "50000" = 50000
     *
     * @param value The string value to parse
     * @return The parsed integer value, or 0 if parsing fails
     */
    static int parseGpValue(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return 0;
        }

        String cleaned = value.trim().toLowerCase().replace(",", "");

        try
        {
            double multiplier = 1;

            if (cleaned.endsWith("m"))
            {
                multiplier = 1_000_000;
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }
            else if (cleaned.endsWith("k"))
            {
                multiplier = 1_000;
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            }

            double parsed = Double.parseDouble(cleaned);
            return (int) (parsed * multiplier);
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }
    @ConfigSection(
        name = "Tracking Options",
        description = "Choose what to track",
        position = 0
    )
    String trackingSection = "tracking";

    @ConfigSection(
        name = "Loot Settings",
        description = "Configure loot tracking options",
        position = 1
    )
    String lootSection = "loot";

    @ConfigSection(
        name = "Clue Scrolls",
        description = "Configure clue scroll tracking options",
        position = 2
    )
    String clueSection = "clue";

    @ConfigSection(
        name = "Video Settings",
        description = "Configure local video recording quality",
        position = 3
    )
    String videoSection = "video";

    // ===== Tracking Options =====

    @ConfigItem(
        keyName = "trackLevelUps",
        name = "Track Level-ups",
        description = "Automatically save level-ups locally",
        section = trackingSection,
        position = 0
    )
    default boolean trackLevelUps()
    {
        return true;
    }

    @ConfigItem(
        keyName = "trackQuests",
        name = "Track Quests",
        description = "Automatically save quest completions locally",
        section = trackingSection,
        position = 1
    )
    default boolean trackQuests()
    {
        return true;
    }

    @ConfigItem(
        keyName = "trackLoot",
        name = "Track Loot Drops",
        description = "Automatically save loot drops locally",
        section = trackingSection,
        position = 2
    )
    default boolean trackLoot()
    {
        return true;
    }

    @ConfigItem(
        keyName = "trackCollectionLog",
        name = "Track Collection Log",
        description = "Automatically save collection log updates locally",
        section = trackingSection,
        position = 3
    )
    default boolean trackCollectionLog()
    {
        return true;
    }

    @ConfigItem(
        keyName = "trackDeaths",
        name = "Track Deaths",
        description = "Automatically save death events locally",
        section = trackingSection,
        position = 4
    )
    default boolean trackDeaths()
    {
        return true;
    }

    @ConfigItem(
        keyName = "trackPets",
        name = "Track Pet Drops",
        description = "Automatically save pet drop events locally with extended 20-second video capture",
        section = trackingSection,
        position = 5
    )
    default boolean trackPets()
    {
        return true;
    }

    // ===== Loot Settings =====

    @ConfigItem(
        keyName = "minimumLootValue",
        name = "Minimum Loot Value",
        description = "Only save loot drops worth at least this amount. Use k for thousands, m for millions (e.g., 100k, 1.5m). Minimum: 100k",
        section = lootSection,
        position = 0
    )
    default String minimumLootValue()
    {
        return "100k";
    }

    /**
     * Gets the minimum loot value as an integer, enforcing the 100k minimum.
     * Parses k/m suffixes (e.g., "100k" = 100000, "1.5m" = 1500000).
     *
     * @return The minimum loot value in GP, at least 100k
     */
    static int getMinimumLootValue(OsrsTrackerConfig config)
    {
        int value = parseGpValue(config.minimumLootValue());
        return Math.max(value, MINIMUM_LOOT_VALUE);
    }

    // ===== Clue Scroll Settings =====

    @ConfigItem(
        keyName = "trackClueScrolls",
        name = "Track Clue Scrolls",
        description = "Automatically save clue scroll rewards locally",
        section = clueSection,
        position = 0
    )
    default boolean trackClueScrolls()
    {
        return true;
    }

    @ConfigItem(
        keyName = "clueScreenshot",
        name = "Include Screenshot/Video",
        description = "Capture a screenshot and video of the clue reward interface",
        section = clueSection,
        position = 1
    )
    default boolean clueScreenshot()
    {
        return true;
    }

    // ===== Video Settings =====

    @ConfigItem(
        keyName = "videoQuality",
        name = "Recording Quality",
        description = "Choose local video recording quality. Higher quality means larger files.",
        section = videoSection,
        position = 0
    )
    default VideoQuality videoQuality()
    {
        return VideoQuality.getDefault();
    }
}
