# Parchment design

Parchment is for reading and steering long conversations with models, on Windows and Android. The design treats a conversation as a page, not a chat thread.

## Principles

- **The page is the container.** Replies sit directly on the canvas. Don't put messages in cards.
- **One bold mark.** The lapis rule (`Modifier.rubricMark`) marks your prompts, the current chat, the current tab and the current settings page. Don't add a second decorative device.
- **Controls appear when needed.** On pointer devices message and row actions show on hover or keyboard focus. On touch, the newest turns show theirs and older ones open from the More button.
- **Say it plainly.** Sentence case, no all-caps labels, no internal terms in the interface ("Update", not "OTA").
- **Monospace is for code.** Numbers use the interface font with tabular figures.

## Tokens

| Token | Lapis on Vellum | Role |
|---|---|---|
| Canvas | `#EFEEE3` | The page |
| Sheet | `#E6E5D8` | Sidebars |
| Card | `#F8F7EF` | Inputs and the current item |
| Ink | `#1D2230` | Text |
| Lapis | `#2A4A9E` | The rule, primary actions, selection |
| Rubric red | `#B3261E` | Errors and destructive actions |
| Gold leaf | `#8F6A12` | Secondary accent |

Colors come from `ThemeConfig`, so every preset keeps working. Lapis at Night is the dark counterpart.

## Reviewing changes

`python scripts/verify.py test UiGalleryTest` renders the real app at phone and desktop sizes into `.parchment-local/verification/gallery/`, using sample chats in the test sandbox.
