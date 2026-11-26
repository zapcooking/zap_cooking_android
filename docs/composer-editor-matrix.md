# Composer editing: the editor matrix

Every composer in this app, and any field where typed text has to come out exactly as it was
written, is checked against **[editor-matrix](https://github.com/dmnyc/editor-matrix)**: one
list of text-editing cases shared by Zap Cooking (web, iOS, Android) and Sidecar, run with real
input. Read this before changing a composer, and run the matrix before and after the change.

## Why

The composer bugs that reach people are ordinary text editing nobody checked: a space or a
blank line typed at the start of a note disappears, a word typed against a mention joins the
mention, Delete in front of a mention does nothing, rich text pasted or dragged in from a web
page keeps its fonts and colors, Undo after clearing the box brings nothing back. A person
expects every text box to behave like Notes or TextEdit. The matrix writes that expectation
down as cases, so a change is checked against all of it, not only the case that prompted it.

## What it covers here

- **Notes and replies:** `app/src/main/kotlin/cooking/zap/app/ui/screen/ComposeScreen.kt`
  and `viewmodel/ComposeViewModel.kt`, mentions in `ui/component/MentionVisualTransformation.kt`
  and `repo/MentionSearchRepository.kt`, attachments and options in
  `ui/component/ComposerAttachments.kt` and `ComposerMoreSheet.kt`.
- **Recipes:** `ui/screen/RecipeComposeScreen.kt` and `viewmodel/RecipeComposeViewModel.kt`.
- **Other places text is published as written:** alt text (`ui/component/AltTextEditorSheet.kt`),
  the first-post onboarding screen, list editors, profile fields.

## The text that is checked

A case compares **the text the app would publish or save as a draft**, not what the screen
shows. For a note or a reply, that is the event's `content` as the view model builds it (after
any mention transformation is turned back into `nostr:` references). For a recipe, the
markdown built from its fields. A screen that shows one thing and publishes another fails.

## How to use it

1. Clone it beside this repository: `git clone https://github.com/dmnyc/editor-matrix`.
   `cases.js` is the list; `cases.json` is the same list for non-JavaScript runners.
2. This app has no runner yet; the first composer change writes one: a Compose UI test (or
   instrumentation test) that reads `cases.json` from test assets and runs each case by id,
   with `performTextInput`, `performKeyInput` for arrows and deletes, and
   `ClipboardManager.setPrimaryClip(ClipData.newHtmlText(...))` before a paste. Android word
   movement differs from the Mac (Ctrl+Right stops at the start of the next word), so the
   runner reports `platform: 'android'` and lists the case ids whose expectation differs, with
   the reason. Test with Gboard and Samsung Keyboard as well: their composing text behaves like
   an input method. The editor-matrix guide `docs/writing-an-adapter.md` describes the contract.
3. Run the matrix on the unchanged code first, so you know which failures were already
   there, then again after the change. A change to a composer does not add failures.
4. A composer bug that no case caught gets a case in editor-matrix, added before the fix and
   seen to fail. Keep cases app-neutral; behavior only this app has (mention chips, recipe
   sections) is tested in this repository.
5. When a case fails, decide whether the app is wrong or the expectation is. The reference is
   platform editing behavior (macOS and iOS for the shared expectations); a case whose
   expectation is wrong is fixed in editor-matrix with its reason, never skipped quietly.

## Where composers usually break

From the first full run against Sidecar's composers (Chrome and Firefox), the areas worth
checking first in any composer:

- **An empty editor.** Clearing the box when its trimmed text is empty eats a leading space
  or blank line as it is typed, and resetting its contents wipes the undo history (Select
  All, Delete, Undo then restores nothing).
- **Line breaks.** A first line, blank lines between paragraphs, a last empty line: each must
  survive a save and reopen, and serialize to the same `\n`s that were typed.
- **Paste.** Rich text must arrive as plain text (or as the markdown the editor writes), a
  clipboard holding only HTML must still paste its text, Windows line endings (`\r\n`) must
  not leave a stray `\r`, and a picture inside copied HTML must not sit inline unuploaded.
- **Drag and drop.** Text dragged in from a page bypasses the paste handler entirely; an
  editor that cleans paste but not drop shows bold, colored text it will not publish.
- **Mentions and other inline tokens.** Typing, deleting and moving the caret at either edge
  of a mention, and what is copied when a mention is selected.
- **Emoji and other scripts.** Backspace removes a whole emoji (flags, skin tones, family
  sequences), word moves work in Cyrillic and accented text, and right-to-left text keeps
  its direction.
