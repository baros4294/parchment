# Changelog

What changed in each release, newest first. From 2.1.7 on, release-please writes these entries from commit messages that follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:` and `fix:` appear here and set the next version); edit wording in its release PR before merging it.

## [2.1.7](https://github.com/baros4294/parchment/compare/v2.1.6...v2.1.7) (2026-11-12)


### Fixes

* run keyboard shortcuts when nothing has focus ([5051e0b](https://github.com/baros4294/parchment/commit/5051e0ba5bd770d9edf5db3dbb0af584e8ad3982))
* run keyboard shortcuts when nothing has focus ([cf20a2f](https://github.com/baros4294/parchment/commit/cf20a2f72975f984081ccf3e792e05622e02dda3))

## 2.1.6

- **Windows: chats, model lists and update checks work again.** Since 2.1.2 the Windows app's smaller build removed a part Ktor needs for JSON, so every request failed with "Could not initialize class io.ktor.serialization.kotlinx.ExtensionsJvmKt". **On Windows 2.1.2 or 2.1.3, install this version by hand once** (download the installer below and run it; chats and settings are kept): the broken versions can't update themselves. Android was not affected.
- Releases now fail to build if the shrinking step removes any class Java looks up by name.

## 2.1.3

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
