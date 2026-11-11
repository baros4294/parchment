# Changelog

What changed in each release, newest first. Add to **Unreleased** with each change; the release workflow publishes that section as the release notes. After a release, rename it to the version and start a new, empty Unreleased.

## Unreleased

- **Ctrl+K command palette:** find any action, settings page or chat by typing. New shortcuts: Ctrl+N new chat, Ctrl+, settings, Ctrl+B sidebar.
- **Undo for deleted chats:** deleting a chat, or every chat of an assistant, can be undone from the notice for 8 seconds. New installs no longer ask for confirmation first.
- **First run:** without an API key, the empty chat says so and opens provider setup in one click. The models list explains why it's empty and can fetch models right there.
- **Themes:** shown as small previews instead of a list of names.
- **Large text:** at 130% system text and above, the phone composer uses two rows instead of wrapping single controls.
- **Accessibility:** accent-colored text meets 4.5:1 contrast on every theme (the default and Linear Carbon were slightly short).
- **Anthropic prompt caching on long agent runs:** older tool results were shortened one more each step, which made most of every request miss Anthropic's cache. They're now shortened in batches like the other providers: about 69% of each request is reused instead of 21%.
- **Low-end devices:** overlays skip their shadow on devices under 4 GB; Reduce motion also stops the chat's scroll animations.
- Security policy, code of conduct, issue templates for features and questions, and third-party font notices; the fonts' licence (OFL) now ships with both apps.
- Groundwork for translations: interface text is gathered in one table.

## 2.1.2

- Sidebar, settings list and icons grow with the system text size, so labels keep whole words at 200%.
- Disabled buttons keep an outline, so they stay visible on dark themes.
- A streaming reply no longer rebuilds the sidebar's lists every frame.
- Design tokens for spacing, corners, lines, icons and motion; Reduce motion now turns every animation off.
- Windows: release jars shrunk with ProGuard (35 MB to 22 MB); README screenshots; the Android R8 mapping file is attached to releases.

## 2.1.1

- JDK 25, Gradle 9.7, Android Gradle Plugin 9.3, Kotlin 2.4 and Compose 1.12. The Android app is built by its own module.

## 2.1.0

- Release titles show only the version.
- Faster start-up and a smaller app on low-end devices: Android APK from 15.5 MB to 4.2 MB, a smaller Windows installer, and fonts without unused icons.

Earlier releases are listed on the [releases page](https://github.com/baros4294/parchment/releases).
