# Parchment design

Parchment is for reading and steering long conversations with models, on Windows and Android. Chats show as messenger bubbles by default, with yours on the right and the assistant's on the left. The Page view sets the same conversation as one continuous page.

## Principles

- **Bubbles hold only the message.** Model name, reasoning and actions sit outside the bubble, so it stays one quiet shape.
- **One bold mark.** The accent rule (`Modifier.rubricMark`) marks your prompts in Page view, the current chat, the current tab and the current settings page. Don't add a second decorative device.
- **Controls appear when needed.** On pointer devices message and row actions show on hover or keyboard focus. On touch, the newest turns show theirs and older ones open from the More button.
- **Explain on hover.** Buttons and chips show a short hint after the pointer rests on them. `hint` defaults to the control's name; pass a sentence when the name doesn't say what happens.
- **Say it plainly.** Sentence case, no all-caps labels, no internal terms in the interface ("Update", not "OTA").
- **Monospace is for code and identifiers.** Counts use the interface font with tabular figures.

## Tokens

Colors come from `ThemeConfig`, so every preset keeps working. Parchment & Espresso Ink is the default.

| Token | Default | Role |
|---|---|---|
| Canvas | `#F4ECD8` | The page |
| Sheet | `#EBE0C5` | Sidebars |
| Card | `#FAF6EB` | Inputs and the current item |
| Ink | `#261C14` | Text |
| Accent | `#8B5A2B` | The rule, primary actions, selection |
| Danger | `#A62B2B` | Errors and destructive actions |

Everything else lives in `ui/DesignTokens.kt`. New UI uses these names, not literal numbers; older screens move over when they are next reworked.

- **Space:** 2, 4, 6, 8, 10, 12, 16, 24, 48 dp (`Space.s8`); 48 is also the smallest touch target. Related items sit closer than unrelated ones.
- **Type:** 11, 12, 13, 14, 15, 16, 17, 19, 21 sp. Nothing below 11. Text grows with the system text size, up to 200%.
- **Corners:** 4, 6, 8, 10, 12 dp, 16 for bubbles, or fully round (`Radius`).
- **Lines:** hairline 0.75 dp, emphasis 1, selected 1.5, focus ring 2 (`LineWidth`).
- **Icons:** 10, 12, 14, 16, 20 dp (`IconSize`), even sizes only, so strokes land on whole pixels.
- **Elevation:** flat, except things that float above the page (notifications, menus): one 8 dp shadow (`Elevation`).
- **Motion:** 110 ms leaving, 150 arriving, 180 travelling, 250 overlays leaving, springs without bounce for size changes (`Motion`). Always through `motionTween` and `motionSpring`, which turn instant under Reduce motion.
- **Focus:** keyboard focus draws a 2 dp accent ring; focus from a click draws none.
- **Text size:** fixed columns (`textScaledWidth`) and icons grow with the system text size, up to 1.6 times at 200%, so labels keep whole words. Short labels in tight places take one line with an ellipsis.
- **Disabled:** a disabled button keeps its outline and dims its fill and text, so it still reads as a button.
- **Buttons:** Primary for the main action, Secondary and Outline for the rest, Ghost in toolbars, Danger for destructive actions.

## What we learned from award-winning apps

Studied 2026-11 (iA Writer, Things, Linear, Raycast, Bear, Craft, Apple Design Award criteria). Ideas only, never assets or branding. Each lesson names the Parchment change it leads to.

| Lesson | Seen in | In Parchment |
|---|---|---|
| Typography is the interface: a reading measure, a clear type scale, nothing competing with the text | iA Writer, Bear | One reading width and one type scale (above) |
| Calm by default: secondary actions recede until wanted | Things, Linear | Controls appear on hover; at large text the phone composer moves its actions to a second row |
| Keyboard first: every action reachable without a pointer, one palette to find them | Linear, Raycast | Ctrl+K command palette; Ctrl+N, Ctrl+, and Ctrl+B |
| Undo instead of asking | Things, Linear | Deleted chats come back from Undo on the notice; the confirm dialog is optional |
| Empty states teach the next step | Things, Craft | Without a key, the empty chat leads into provider setup; empty lists say why and offer the fix |
| Motion explains, briefly; it never delays | Linear, Things | Short timings above; Reduce motion makes all of it instant |
| Inclusive by design, not by audit | Apple Design Awards (Inclusivity) | 200% text, tested contrast on every theme, labeled controls; screen-reader passes by the user |
| Density that scales: compact on a phone, airy on a desktop | Linear, Craft | One spacing scale; columns grow with text size |

## Audit, 2026-11

Taken from the gallery (light and dark, phone and desktop, 100% and 200% text) and ranked by harm. Each item names the task that fixes it; remove it once fixed.

All eight issues are fixed: columns, icons and the phone composer at large text; the disabled button in dark; Undo for deleted chats; a first run that leads to provider setup; a models list that explains itself; theme previews. Two smaller notes remain: at 200% text the phone shows only the start of the chat title, and Providers' middle column is mostly empty with few providers. Run a new audit after the next large UI change.

Sources: [How we redesigned the Linear UI](https://linear.app/now/how-we-redesigned-the-linear-ui), [iA Writer vs Word](https://ia.net/topics/writer-vs-word), [Apple Design Awards](https://developer.apple.com/design/awards/).

## Icons

`KissIconGlyphs.kt` holds one family on a 24-unit grid: live area 3 to 21, circles of radius 9, corners of radius 2.5, arrowheads 6 deep. Stroke weight follows icon size. Add new icons there, as paths, on the same grid.

The app icon is one nib over a line of ink. `installer/parchment-icon.ico`, `resources/icon/parchment-icon.png` and the Android launcher use the same shape.

## Windows

The window uses the native caption, so minimize, maximize, Snap Layouts and rounded corners are the system's own. `WindowsChrome` tints it and its text to the theme. The caption shows the app icon and "chat title — Parchment" (or "Settings — Parchment"); while a reply generates it shows the run's progress instead.

## Reviewing changes

`python scripts/verify.py test UiGalleryTest` renders the real app at phone and desktop sizes, in light and dark and at 200% text (`*-text200-*`), plus an icon sheet, into `.parchment-local/verification/gallery/`, using sample chats in the test sandbox.

## Words on screen

Write for a capable person who is not a developer. Say what a control does, not how it works.

Interface text lives in `ui/Strings.kt`, one table for translation: a `val` for fixed text, a function for text with values (`Strings.clearedMessagesIn(title)`). Add new text there, not in a screen.

| Instead of | Say |
|---|---|
| Wire dialect, protocol | API format |
| Base URL, endpoint | Server URL, address |
| Compact, compaction | Shorten the conversation |
| Context window | Conversation limit |
| Steer | Send now |
| Fork | Branch |
| Mount | Choose folder |
| Reasoning | Thinking |
| Cache read, cache write | Reused, saved for reuse |
| Utility requests | Background requests |

Keep "API key", "tokens", "Markdown" and "World Book": people meet these terms outside Parchment. Show a term of art such as temperature beside a plain description.
