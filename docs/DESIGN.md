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

Everything else lives in `ui/DesignTokens.kt`. New UI uses these names, not literal numbers; older screens move over as each one is reworked (13.4).

- **Space:** 2, 4, 6, 8, 10, 12, 16, 24, 48 dp (`Space.s8`); 48 is also the smallest touch target. Related items sit closer than unrelated ones.
- **Type:** 11, 12, 13, 14, 15, 16, 17, 19, 21 sp. Nothing below 11. Text grows with the system text size, up to 200%.
- **Corners:** 4, 6, 8, 10, 12 dp, 16 for bubbles, or fully round (`Radius`).
- **Lines:** hairline 0.75 dp, emphasis 1, selected 1.5, focus ring 2 (`LineWidth`).
- **Icons:** 10, 12, 14, 16, 20 dp (`IconSize`), even sizes only, so strokes land on whole pixels.
- **Elevation:** flat, except things that float above the page (notifications, menus): one 8 dp shadow (`Elevation`).
- **Motion:** 110 ms leaving, 150 arriving, 180 travelling, 250 overlays leaving, springs without bounce for size changes (`Motion`). Always through `motionTween` and `motionSpring`, which turn instant under Reduce motion.
- **Focus:** keyboard focus draws a 2 dp accent ring; focus from a click draws none.
- **Buttons:** Primary for the main action, Secondary and Outline for the rest, Ghost in toolbars, Danger for destructive actions.

## What we learned from award-winning apps

Studied 2026-11 (iA Writer, Things, Linear, Raycast, Bear, Craft, Apple Design Award criteria). Ideas only, never assets or branding. Each lesson names the Parchment change it leads to.

| Lesson | Seen in | Parchment change |
|---|---|---|
| Typography is the interface: a reading measure, a clear type scale, nothing competing with the text | iA Writer, Bear | Keep the reading width; one type scale (above); quieter chrome around replies (13.4) |
| Calm by default: secondary actions recede until wanted | Things, Linear | Already: controls on hover. Next: fold rarely used composer chips into one menu (13.4) |
| Keyboard first: every action reachable without a pointer, one palette to find them | Linear, Raycast | Ctrl+K command palette and shortcuts for main actions (13.5) |
| Undo instead of asking | Things, Linear | Reversible deletes undo from a notice instead of a confirm dialog (13.5) |
| Empty states teach the next step | Things, Craft | First run without a provider becomes a guided setup (13.4) |
| Motion explains, briefly; it never delays | Linear, Things | Short timings above; Reduce motion makes all of it instant (13.3, 13.7) |
| Inclusive by design, not by audit | Apple Design Awards (Inclusivity) | Screen reader passes, 200% text, contrast on every theme (13.6) |
| Density that scales: compact on a phone, airy on a desktop | Linear, Craft | One spacing scale, applied per surface (13.4) |

## Audit, 2026-11

Taken from the gallery (light and dark, phone and desktop, 100% and 200% text) and ranked by harm. Each item names the task that fixes it; remove it once fixed.

1. **200% text, desktop:** the sidebar and settings list keep a fixed 285 dp width, so labels break mid-word ("Preference / s", "Assist / ants") and the search box hides its hint. Let fixed-width columns grow with text size and never break inside a word (13.6).
2. **200% text:** icons keep their size while text doubles, so they read as specks beside labels. Inline icons should follow text size (13.6).
3. **200% text, phone:** the composer wraps Tools onto a second row and the chat title shows only two words. Collapse rarely used composer controls into one menu (13.4).
4. **Destructive actions in quiet places:** "Clear all" sits beside a single chat with no undo. Undo instead of confirm, and a less prominent place (13.5).
5. **First run:** the empty chat says what to do but not how to add a provider; with no provider it should lead into setup (13.4).
6. **Settings, Providers:** three columns on a desktop leave the middle one mostly empty; the models empty state relies on an icon (13.4).
7. **Settings, Appearance:** themes are a long list of radio rows; small previews would let people compare them at a glance (13.4).
8. **Dark:** contrast passes; the disabled Send button is hard to tell from the composer. Give disabled buttons a visible outline (13.6).

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
