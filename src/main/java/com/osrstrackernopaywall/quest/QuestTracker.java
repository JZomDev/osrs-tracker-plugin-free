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
package com.osrstrackernopaywall.quest;

import com.google.gson.JsonObject;
import com.osrstrackernopaywall.OsrsTrackerConfig;
import com.osrstrackernopaywall.api.ApiClient;
import com.osrstrackernopaywall.video.VideoRecorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.VarPlayer;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles quest completion detection and tracking.
 *
 * Quest completions are detected by monitoring the quest points VarPlayer value.
 * When quest points increase, the quest name is extracted from the quest completion widget.
 * This approach is inspired by the Quest Helper plugin's quest detection system.
 */
@Slf4j
@Singleton
public class QuestTracker
{
    // Widget ID constants for quest completion interface
    private static final int QUEST_COMPLETE_WIDGET_GROUP = 153;
    private static final int QUEST_COMPLETE_WIDGET_CHILD = 4;

    private static final String QUEST_COMPLETE_TEXT_MARKER = "Quest complete!";

    private final Client client;
    private final ApiClient apiClient;
    private final OsrsTrackerConfig config;
    private final VideoRecorder videoRecorder;

    // State tracking to detect quest completions
    private int previousQuestPoints = 0;
    private String lastCompletedQuestName = null;

    private static final int SYNC_DELAY_TICKS = 5; // Wait ~3 seconds after login

    @Inject
    public QuestTracker(Client client, ApiClient apiClient, OsrsTrackerConfig config, VideoRecorder videoRecorder)
    {
        this.client = client;
        this.apiClient = apiClient;
        this.config = config;
        this.videoRecorder = videoRecorder;
    }

    /**
     * Initializes the quest tracker when the player logs in.
     * Stores the current quest points to detect future changes.
     * Schedules a quest sync after a short delay to ensure data is loaded.
     */
    public void initializeQuestTracking()
    {
        previousQuestPoints = getCurrentQuestPoints();
        lastCompletedQuestName = null;
        log.debug("Initialized quest tracking with {} quest points", previousQuestPoints);
    }

    /**
     * Resets all quest tracking state. Should be called when the plugin shuts down.
     */
    public void resetQuestTracking()
    {
        previousQuestPoints = 0;
        lastCompletedQuestName = null;
    }

    /**
     * Checks for quest completion by monitoring quest point changes.
     * Should be called when a VarbitChanged event occurs.
     *
     * Quest completions are detected when:
     * 1. Quest points increase from a non-zero previous value
     * 2. A quest name can be extracted from the completion widget
     * 3. The quest name is different from the last completed quest
     */
    public void checkForQuestCompletion()
    {
        if (!config.trackQuests())
        {
            return;
        }

        int currentQuestPoints = getCurrentQuestPoints();

        // Check if quest points increased (and we have a valid baseline)
        if (currentQuestPoints > previousQuestPoints && previousQuestPoints > 0)
        {
            log.debug("Quest points increased: {} -> {}", previousQuestPoints, currentQuestPoints);

            // Attempt to extract the quest name from the completion widget
            String questName = extractQuestNameFromWidget();

            if (questName != null && !questName.equals(lastCompletedQuestName))
            {
                log.debug("Quest completed: {}", questName);
                sendQuestCompletionToApi(questName);
                lastCompletedQuestName = questName;
            }

            previousQuestPoints = currentQuestPoints;
        }
    }

    /**
     * Extracts the quest name from the quest completion widget.
     *
     * The quest completion widget (group 153, child 4) displays the completed quest's name
     * along with "Quest complete!" text. This method extracts and cleans the quest name.
     *
     * @return The quest name if found, null otherwise
     */
    private String extractQuestNameFromWidget()
    {
        Widget questWidget = client.getWidget(QUEST_COMPLETE_WIDGET_GROUP, QUEST_COMPLETE_WIDGET_CHILD);

        if (questWidget != null && !questWidget.isHidden())
        {
            String widgetText = questWidget.getText();
            if (widgetText != null && !widgetText.isEmpty())
            {
                // Remove HTML/formatting tags and clean up the text
                String cleanedText = Text.removeTags(widgetText);

                // Remove the "Quest complete!" marker to isolate the quest name
                cleanedText = cleanedText.replace(QUEST_COMPLETE_TEXT_MARKER, "").trim();

                return cleanedText;
            }
        }

        return null;
    }

    /**
     * Gets the player's current quest points from the game state.
     *
     * @return The current quest point count
     */
    private int getCurrentQuestPoints()
    {
        return client.getVarpValue(VarPlayer.QUEST_POINTS);
    }

    /**
     * Sends a quest completion event to the API with video capture.
     *
     * @param questName The name of the completed quest
     */
    private void sendQuestCompletionToApi(String questName)
    {
        // Capture video with the quest completion event
        videoRecorder.captureEventVideo((screenshotBase64, videoBase64) -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("quest_name", questName);

            apiClient.sendEventToApi(
                "/api/webhooks/quest_complete",
                payload.toString(),
                "quest completion: " + questName,
                screenshotBase64,
                videoBase64
            );
        });
    }
}
