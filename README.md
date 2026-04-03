# OSRS Tracker (Local Capture Mode)

This RuneLite plugin captures gameplay events and stores videos/screenshots locally.

## What It Does

- Captures level-ups, quests, loot drops, collection log entries, deaths, clue rewards, and pet drops.
- Saves quick captures from the sidebar button.
- Stores files under `RuneLite.RUNELITE_DIR/videos/<event_type>/`.

## File Layout

For each event, the plugin writes files using:

- `<event_type>_<timestamp>.mp4`
- `<event_type>_<timestamp>.png` (when screenshot is captured)
- `<event_type>_<timestamp>.json` (event metadata payload)

Example:

`videos/loot_drop/loot_drop_20260403_213015_442.mp4`

## Installation

### From Plugin Hub

Search for **OSRS Tracker Free** in RuneLite Plugin Hub.

### Build from Source

```bash
git clone https://github.com/dennisdevulder/osrs-tracker-plugin.git
cd osrs-tracker-plugin
./gradlew build
```

## Notes

- This build is offline/local-only. Online API sync, bingo integration, and item snitch are removed.
- Video quality is configurable in plugin settings.
- Login-sensitive interfaces are still blurred in captured media.

## License

BSD 2-Clause License - See `LICENSE`.

## Sidebar Panel

The plugin adds an **OSRS Tracker** panel to your RuneLite sidebar with:

- **Quick Capture** button - Manually capture video clips
- **Status indicator** - Shows recording/uploading progress
- **Bingo Status** - Shows active event and tracking progress

## Privacy & Security

- **Tracking disabled by default** - No network requests until you configure your API token
- **Token stored locally** - API token is stored in your local RuneLite configuration
- **Login screen protection** - Video recording automatically blurs login screens

## Troubleshooting

### Item Snitch Not Working

1. Make sure you've joined a group on osrs-tracker.com
2. Check that your group has items added to track
3. Open your bank or shared chest to trigger a scan
4. Check RuneLite logs: Help → Open Logs Folder

### Events Not Sending

1. Check that your **API Token** is valid
2. Make sure you're logged into the game
3. Check RuneLite logs for errors

### Collection Log Not Detected

Enable in OSRS Settings:
- **Notifications** → **Collection log**: ON
- **Chat** → **Game messages**: ON

## Support

- **Issues**: [GitHub Issues](https://github.com/dennisdevulder/osrs-tracker-plugin/issues)
- **Website**: [osrs-tracker.com](https://osrs-tracker.com)

## License

BSD 2-Clause License - See [LICENSE](LICENSE) for details.

## Credits

- [RuneLite](https://runelite.net/) - Open source OSRS client
