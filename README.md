# OSRS Tracker No Paywall (Local Capture Mode)

This RuneLite plugin captures gameplay events and stores videos/screenshots locally.

It's free. No API token required. No online sync. No paywall.

If you want a paywall version of this check out https://github.com/dennisdevulder/osrs-tracker-plugin

The recording looks smoother if you have a stronger computer. 

## What It Does

- Captures level-ups, quests, loot drops, collection log entries, deaths, clue rewards, and pet drops.
- Saves quick captures from the sidebar button.
- Stores files under `.runelite/videos/<event_type>/`.

## File Layout

For each event, the plugin writes files using:

- `<event_type>_<timestamp>.avi`
- `<event_type>_<timestamp>.png` (when screenshot is captured)
- `<event_type>_<timestamp>.json` (event metadata payload)


## Installation

### From Plugin Hub

Search for **OSRS Tracker Free** in RuneLite Plugin Hub.

## Notes

- This build is offline/local-only.
- Video quality is configurable in plugin settings.
- Login-sensitive interfaces are still blurred in captured media.

## License

BSD 2-Clause License - See `LICENSE`.

## Sidebar Panel

The plugin adds an **OSRS Tracker** panel to your RuneLite sidebar with:

- **Quick Capture** button - Manually capture video clips
- **Status indicator** - Shows recording/uploading progress

## Privacy & Security

- **Login screen protection** - Video recording automatically blurs login screens

## Troubleshooting


### Collection Log Not Detected

Enable in OSRS Settings:
- **Notifications** → **Collection log**: ON
- **Chat** → **Game messages**: ON

### I can't play the recorded videos!
- Remux the recordings using OBS or ffmpeg. The plugin captures in a raw format that may not be compatible with all media players.


## Support

- **Issues**: [GitHub Issues](https://github.com/JZomDev/osrs-tracker-plugin-free/issues)

## License

BSD 2-Clause License - See [LICENSE](LICENSE) for details.

## Credits

- [RuneLite](https://runelite.net/) - Open source OSRS client
- [dennisdevulder's repo](https://github.com/dennisdevulder/osrs-tracker-plugin)
