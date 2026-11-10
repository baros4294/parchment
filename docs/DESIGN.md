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

- **Type:** 11, 12, 13, 14, 15, 16, 17, 19, 21 sp. Nothing below 11.
- **Corners:** 4, 6, 8, 10, 12 dp, 16 for bubbles, or fully round.
- **Borders:** 0.75 dp, or 1 dp for emphasis.
- **Buttons:** Primary for the main action, Secondary and Outline for the rest, Ghost in toolbars, Danger for destructive actions.

## Icons

`KissIconGlyphs.kt` holds one family on a 24-unit grid: live area 3 to 21, circles of radius 9, corners of radius 2.5, arrowheads 6 deep. Stroke weight follows icon size. Add new icons there, as paths, on the same grid.

The app icon is one nib over a line of ink. `installer/parchment-icon.ico`, `resources/icon/parchment-icon.png` and the Android launcher use the same shape.

## Windows

The window uses the native caption, so minimize, maximize, Snap Layouts and rounded corners are the system's own. `WindowsChrome` tints it to the theme and hides the caption text and icon from drawing, while the taskbar and Alt+Tab keep the name.

## Reviewing changes

`python scripts/verify.py test UiGalleryTest` renders the real app at phone and desktop sizes, plus an icon sheet, into `.parchment-local/verification/gallery/`, using sample chats in the test sandbox.
