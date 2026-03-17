# MindFlow

MindFlow is an Android focus assistant that combines timer-based deep work, AI screen understanding, and progressive distraction intervention.

## Highlights

- Focus session timer with real-time state updates.
- AI-based focus/distraction assessment from on-screen context.
- Progressive intervention: warning -> lock screen when distraction reaches threshold.
- Configurable lock duration in Settings (default: 60 seconds).
- Login/offline entry and cloud sync support.

## Project Structure

- MindFlowApp: Android application (Java + Gradle).
- MindFlowBackend: model and backend helper scripts.
- docs: static auth callback page for GitHub Pages deployment.

## Quick Start (Android)

1. Open MindFlowApp in Android Studio.
2. Configure local properties and API keys as required by the app.
3. Sync Gradle and run on a physical device.
4. In app settings, grant required permissions:
   - Accessibility
   - Overlay
   - Notification access
   - (Optional) DND fallback

## Focus and Lock Logic

- During focus session, AI marks behavior as focused or distracted.
- Distracted events are counted.
- When distraction count reaches 3, lock screen is triggered.
- Lock duration can be customized in Settings.

## Auth Callback Deployment

- Static callback page is in docs/index.html.
- Recommended deploy target: GitHub Pages (branch + /docs).

## License

Internal / course project. Add a formal license if open-sourcing.
