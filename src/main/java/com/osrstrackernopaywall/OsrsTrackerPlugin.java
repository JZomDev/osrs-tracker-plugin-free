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
package com.osrstrackernopaywall;

import com.google.gson.JsonObject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import com.osrstrackernopaywall.api.ApiClient;
import com.osrstrackernopaywall.skills.SkillLevelTracker;
import com.osrstrackernopaywall.quest.QuestTracker;
import com.osrstrackernopaywall.loot.LootTracker;
import com.osrstrackernopaywall.collectionlog.CollectionLogTracker;
import com.osrstrackernopaywall.death.DeathTracker;
import com.osrstrackernopaywall.clue.ClueScrollTracker;
import com.osrstrackernopaywall.pets.PetTracker;
import com.osrstrackernopaywall.video.VideoRecorder;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OSRS Tracker plugin - Automatically tracks gameplay events and saves them locally.
 *
 * This plugin uses a modular architecture where each type of tracking (skills, quests, loot, etc.)
 * is handled by a dedicated tracker class. The main plugin coordinates these trackers and manages
 * the video recording system.
 *
 * Features:
 * - Level-ups with 10-second video replays
 * - Quest completions with video
 * - Loot drops with video (configurable minimum value)
 * - Clue scroll rewards with video and item details
 * - Collection log updates with video
 * - Death events with video replays
 * - RSN verification to prevent alt account data syncing
 */
@Slf4j
@PluginDescriptor(
    name = "OSRS Tracker",
    description = "Automatically captures level-ups, quest completions, loot drops, clue scrolls, and deaths to local files",
    tags = {"tracker", "levels", "quests", "loot", "collection log", "deaths", "clue", "treasure trails"}
)
public class OsrsTrackerPlugin extends Plugin
{
    // Gauntlet boss NPC IDs (multiple forms/states for each)
    private static final int[] CRYSTALLINE_HUNLLEF_IDS = {9021, 9022, 9023, 9024};
    private static final int[] CORRUPTED_HUNLLEF_IDS = {9035, 9036, 9037, 9038};

    @Inject
    private Client client;

    @Inject
    private OsrsTrackerConfig config;

    @Inject
    private ClientThread clientThread;

    // Modular trackers
    @Inject
    private ApiClient apiClient;

    @Inject
    private SkillLevelTracker skillLevelTracker;

    @Inject
    private QuestTracker questTracker;

    @Inject
    private LootTracker lootTracker;

    @Inject
    private CollectionLogTracker collectionLogTracker;

    @Inject
    private DeathTracker deathTracker;

    @Inject
    private ClueScrollTracker clueScrollTracker;

    @Inject
    private VideoRecorder videoRecorder;

    @Inject
    private PetTracker petTracker;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private EventBus eventBus;

    @Inject
    private ConfigManager configManager;

    // Sidebar navigation button and panel for quick capture
    private NavigationButton quickCaptureButton;
    private OsrsTrackerPanel panel;

    // Track if a quick capture is in progress to prevent spam (thread-safe)
    private final AtomicBoolean quickCaptureInProgress = new AtomicBoolean(false);

    // Cooldown timer to prevent rapid captures (5 second cooldown after completion)
    private static final int COOLDOWN_SECONDS = 5;
    private final AtomicLong lastCaptureCompletedTime = new AtomicLong(0);
    private ScheduledExecutorService cooldownExecutor;

    // Track if we're in an active login session to prevent repeated re-initialization
    // GameState.LOGGED_IN fires frequently during gameplay (region loads, interface closes, etc.)
    // We only want to initialize trackers once per actual login, not on every state change
    private volatile boolean sessionActive = false;

    @Override
    protected void startUp() throws Exception
    {
        log.debug("OSRS Tracker started!");

        // Register clue scroll tracker with event bus
        eventBus.register(clueScrollTracker);

        // Start video recording
        videoRecorder.startRecording();

        // Initialize trackers when already logged in (plugin enabled mid-session)
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            log.debug("Plugin started while logged in - initializing trackers");
            sessionActive = true;
            skillLevelTracker.initializeSkillLevels();
            questTracker.initializeQuestTracking();
        }

        panel = new OsrsTrackerPanel(this::triggerQuickCapture);

        // Load icon for sidebar
        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "quick_capture_icon.png");

        if (icon == null)
        {
            log.warn("Could not load quick_capture_icon.png, creating fallback icon");
            // Create a simple fallback icon (16x16 blue square with camera shape)
            icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = icon.createGraphics();
            g.setColor(new java.awt.Color(93, 173, 226)); // Light blue
            g.fillRect(2, 4, 12, 8);
            g.setColor(new java.awt.Color(26, 82, 118)); // Dark blue
            g.fillOval(5, 5, 6, 6);
            g.setColor(java.awt.Color.WHITE);
            g.fillOval(7, 7, 2, 2);
            g.dispose();
        }
        else
        {
            log.debug("Successfully loaded quick_capture_icon.png");
        }

        // Create navigation button with panel (required for sidebar placement)
        quickCaptureButton = NavigationButton.builder()
            .tooltip("OSRS Tracker - Quick Capture")
            .icon(icon)
            .priority(10)
            .panel(panel)
            .build();

        clientToolbar.addNavigation(quickCaptureButton);
        log.debug("Quick capture button added to sidebar");

    }

    @Override
    protected void shutDown() throws Exception
    {
        log.debug("OSRS Tracker stopped!");

        // Unregister clue scroll tracker from event bus
        eventBus.unregister(clueScrollTracker);

        // Shutdown cooldown executor
        if (cooldownExecutor != null && !cooldownExecutor.isShutdown())
        {
            cooldownExecutor.shutdownNow();
        }

        // Remove sidebar button
        if (quickCaptureButton != null)
        {
            clientToolbar.removeNavigation(quickCaptureButton);
        }

        // Stop video recording
        videoRecorder.stopRecording();

        // Reset session state and all trackers
        sessionActive = false;
        skillLevelTracker.resetSkillTracking();
        questTracker.resetQuestTracking();
    }

    /**
     * Triggers a quick capture when the sidebar button is clicked.
     * Captures 8 seconds of buffered video + 2 seconds after the click.
     * Includes cooldown protection to prevent spam.
     */
    private void triggerQuickCapture()
    {
        // Prevent spam clicking - atomically check and set capture in progress
        if (!quickCaptureInProgress.compareAndSet(false, true))
        {
            log.debug("Quick capture already in progress, ignoring");
            return;
        }

        // Check cooldown
        long now = System.currentTimeMillis();
        long timeSinceLastCapture = now - lastCaptureCompletedTime.get();
        int remainingCooldown = (int) ((COOLDOWN_SECONDS * 1000 - timeSinceLastCapture) / 1000);

        if (remainingCooldown > 0)
        {
            log.debug("Quick capture on cooldown, {} seconds remaining", remainingCooldown);
            panel.setCooldownState(remainingCooldown);
            quickCaptureInProgress.set(false); // Reset since we're not actually capturing
            return;
        }

        // quickCaptureInProgress is already set to true by compareAndSet above

        // Update UI to recording state
        panel.setRecordingState();

        log.debug("Quick capture triggered!");
        clientThread.invokeLater(() ->
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "OSRS Tracker: Quick capture started! Recording 2 more seconds...", null)
        );

        // Capture video (8s buffer + 2s post-click)
        // Use the overloaded method with encoding callback to update UI when encoding starts
        videoRecorder.captureEventVideo(
            // Completion callback - called when encoding is done
            (screenshotBase64, videoBase64) -> {
                panel.setSavingState();

                JsonObject json = new JsonObject();
                json.addProperty("event_type", "quick_capture");

                // Get player RSN if available
                Player localPlayer = client.getLocalPlayer();
                if (localPlayer != null && localPlayer.getName() != null)
                {
                    json.addProperty("rsn", localPlayer.getName());
                }

                // Send to quick capture endpoint
                apiClient.sendEventToApi("/api/webhooks/quick_capture", json.toString(), "quick capture", screenshotBase64, videoBase64);

                // Show feedback in game and update panel
                clientThread.invokeLater(() -> {
                    if (screenshotBase64 != null && videoBase64 != null)
                    {
                        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                            "OSRS Tracker: Quick capture saved locally.", null);
                        panel.setSuccessState();
                    }
                    else if (screenshotBase64 != null)
                    {
                        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                            "OSRS Tracker: Screenshot saved locally.", null);
                        panel.setSuccessState();
                    }
                    else
                    {
                        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                            "OSRS Tracker: Capture failed. Please try again.", null);
                        panel.setErrorState("Capture failed!");
                    }
                });

                // Mark capture as complete and start cooldown
                quickCaptureInProgress.set(false);
                lastCaptureCompletedTime.set(System.currentTimeMillis());

                // Start cooldown countdown in UI
                startCooldownTimer();
            },
            // Encoding start callback - called when recording stops and encoding begins
            () -> panel.setEncodingState()
        );
    }

    /**
     * Starts the cooldown timer that updates the panel UI.
     */
    private void startCooldownTimer()
    {
        if (cooldownExecutor != null && !cooldownExecutor.isShutdown())
        {
            cooldownExecutor.shutdownNow();
        }

        cooldownExecutor = Executors.newSingleThreadScheduledExecutor();

        // Update countdown every second
        cooldownExecutor.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            long timeSinceCapture = now - lastCaptureCompletedTime.get();
            int remainingSeconds = (int) Math.ceil((COOLDOWN_SECONDS * 1000 - timeSinceCapture) / 1000.0);

            if (remainingSeconds <= 0)
            {
                panel.setReadyState();
                cooldownExecutor.shutdown();
            }
            else
            {
                panel.setCooldownState(remainingSeconds);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Handle login events - initialize all trackers.
     *
     * IMPORTANT: GameState.LOGGED_IN fires frequently during gameplay (region loads,
     * interface closes, cutscenes, etc.), not just on actual login. We use sessionActive
     * to ensure we only initialize once per actual login session.
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged gameStateChanged)
    {
        GameState state = gameStateChanged.getGameState();

        if (state == GameState.LOGGED_IN)
        {
            // Only initialize if this is the start of a new session
            if (!sessionActive)
            {
                log.debug("New login session detected - initializing trackers");
                sessionActive = true;
                skillLevelTracker.initializeSkillLevels();
                questTracker.initializeQuestTracking();
            }
            // else: already in an active session, skip re-initialization
        }
        else if (state == GameState.LOGIN_SCREEN)
        {
            // Full logout - reset session and all trackers
            if (sessionActive)
            {
                log.debug("Logout detected - resetting session and trackers");
                sessionActive = false;
                skillLevelTracker.resetSkillTracking();
                questTracker.resetQuestTracking();
            }
        }
        else if (state == GameState.HOPPING)
        {
            // World hop - reset session so we re-initialize after hop completes
            // This ensures we capture fresh skill levels on the new world
            if (sessionActive)
            {
                log.debug("World hop detected - will re-initialize after hop");
                sessionActive = false;
            }
        }
    }

    /**
     * Handle stat changes - delegate to skill level tracker.
     */
    @Subscribe
    public void onStatChanged(StatChanged statChanged)
    {
        if (!config.trackLevelUps())
        {
            return;
        }

        Skill skill = statChanged.getSkill();

        skillLevelTracker.checkForLevelUp(skill);
    }

    /**
     * Handle chat messages - delegate to collection log, clue scroll, and pet trackers.
     */
    @Subscribe
    public void onChatMessage(ChatMessage chatMessage)
    {
        if (chatMessage.getType() != ChatMessageType.GAMEMESSAGE)
        {
            return;
        }

        String message = Text.removeTags(chatMessage.getMessage());

        // Check for collection log updates
        if (config.trackCollectionLog())
        {
            collectionLogTracker.processGameMessage(message);
        }

        // Check for clue scroll completion messages
        if (config.trackClueScrolls())
        {
            clueScrollTracker.processGameMessage(message);
        }

        // Check for pet drops
        if (config.trackPets())
        {
            petTracker.processGameMessage(message);
        }

    }

    /**
     * Handle varbit changes - delegate to quest tracker for quest point tracking.
     */
    @Subscribe
    public void onVarbitChanged(VarbitChanged varbitChanged)
    {
        if (!config.trackQuests())
        {
            return;
        }

        questTracker.checkForQuestCompletion();
    }

    /**
     * Handle loot drops - delegate to loot tracker.
     */
    @Subscribe
    public void onServerNpcLoot(ServerNpcLoot event)
    {
        NPCComposition npc = event.getComposition();
        String npcName = (npc != null) ? npc.getName() : "Unknown";

        // Process for timeline loot tracker (with value threshold)
        if (config.trackLoot())
        {
            lootTracker.processLootDrop(npcName, event.getItems());
        }

    }

    /**
     * Handle chest/raid/event loot via LootReceived (requires built-in Loot Tracker enabled).
     * Only processes EVENT type to avoid duplicating NPC drops already handled by ServerNpcLoot.
     */
    @Subscribe
    public void onLootReceived(LootReceived event)
    {
        if (event.getType() != LootRecordType.EVENT)
        {
            return;
        }

        if (config.trackLoot())
        {
            lootTracker.processLootDrop(event.getName(), event.getItems());
        }
    }

    /**
     * Handle actor deaths - delegate to death tracker.
     */
    @Subscribe
    public void onActorDeath(ActorDeath actorDeath)
    {
        Actor actor = actorDeath.getActor();

        // Track player deaths for timeline
        if (config.trackDeaths())
        {
            deathTracker.processActorDeath(actor);
        }

    }

    /**
     * Handle widget loaded events for clue scroll reward detection.
     */
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        // Check for clue scroll reward widget
        if (config.trackClueScrolls() && event.getGroupId() == clueScrollTracker.getRewardWidgetGroupId())
        {
            clueScrollTracker.onRewardWidgetLoaded();
        }
    }

    /**
     * Handle game ticks - check for quality setting changes.
     */
    @Subscribe
    public void onGameTick(GameTick gameTick)
    {
        // Update video capture rate if quality setting changed
        videoRecorder.updateCaptureRateIfNeeded();

    }

    /**
     * Handle config changes - validate minimum loot value.
     */
    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!"osrstracker".equals(event.getGroup()))
        {
            return;
        }

        // Validate minimum loot value - enforce 100k minimum
        if ("minimumLootValue".equals(event.getKey()))
        {
            String newValue = event.getNewValue();
            int parsedValue = OsrsTrackerConfig.parseGpValue(newValue);

            if (parsedValue < OsrsTrackerConfig.MINIMUM_LOOT_VALUE)
            {
                // Value is below minimum, reset to 100k
                log.debug("Minimum loot value {} ({} GP) is below 100k minimum, resetting to 100k", newValue, parsedValue);
                configManager.setConfiguration("osrstracker", "minimumLootValue", "100k");

                // Notify user
                clientThread.invokeLater(() ->
                    client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                        "OSRS Tracker: Minimum loot value must be at least 100k GP", null)
                );
            }
        }
    }

    @Provides
    OsrsTrackerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(OsrsTrackerConfig.class);
    }
}
