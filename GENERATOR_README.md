# Sketch → real project, wired up

What changed from the sketch-to-prompt version:

1. **`app/src/main/assets/boilerplate/`** — your `init.zip` Hello World project,
   copied in verbatim. This is the template every export starts from.

2. **`CodeGenerator.kt`** — the template-fill engine:
   - Copies `assets/boilerplate/` into a working directory.
   - Takes one `ScreenExport` per `ScreenPage` (Home/Onboarding/Splash/Blank -
     see `ScreenPage.kt`), not just the currently active screen.
   - Generates one Fragment + layout per screen (`HomeFragment`/
     `fragment_home.xml`, etc.) and a `MainActivity` that hosts them in a
     single `fragment_container`, wiring up navigation between them - see
     "Navigation flow" below.
   - Also overwrites `strings.xml` (per-screen label resources),
     `app/build.gradle.kts` (Material + `fragment-ktx` deps), `themes.xml`
     (Material3).
   - Leaves the Gradle wrapper config and manifest from the template
     untouched.
   - Zips the result to `generated_app.zip` in the app's cache dir.

3. **`PromptGenerator.kt`** — takes the same `List<ScreenExport>` and writes
   one prompt describing every screen plus (when there's more than one) the
   same navigation flow `CodeGenerator` scaffolds, so a prompt built from a
   sketch and a `.zip` exported from the same sketch describe the same app.

4. **`SketchActivity.kt`** — `buildScreenExports()` reads every `ScreenPage`'s
   own `SketchCanvasView` (via `screenCanvases`), not just the on-screen one,
   before calling either generator.

5. **`SketchActivity.kt` / `SketchDialogs.kt`** — the tools sheet has an
   "Export project (.zip)" button next to "Generate prompt". Tapping it runs
   the generator and opens the share sheet (via `FileProvider`) so the zip
   can be saved to Drive, sent to a computer, etc.

6. **`file_paths.xml` + `AndroidManifest.xml`** — the FileProvider plumbing
   required to share a file from cache dir on modern Android.

## Navigation flow
Only emitted for the screen types actually present in the sketch (a
Home-only project gets none of this - just one `HomeFragment` shown
immediately):
- **Splash** (if present): shown on launch; after ~1.2s automatically calls
  `MainActivity.advanceFromSplash()`.
- **Onboarding** (if present): shown once per install. Its first
  `MaterialButton` (e.g. sketch's "Get started" button) is wired to
  `MainActivity.finishOnboarding()`, which sets a `SharedPreferences` flag
  (`app_prefs` / `onboarding_complete`) and never shows Onboarding again.
- **Home**: the default landing screen - shown after Splash/Onboarding
  resolve, and immediately on every later cold start once onboarding is
  done.
- **Blank**: generated as its own Fragment/layout but intentionally left out
  of the automatic flow - a scratch screen to wire up manually (e.g. from a
  nav bar) if the sketch needs one.

## Try it
Unzip the output the app produces, then in that folder:
```
./gradlew assembleDebug
```
It's a real, complete Gradle project — same shape as the boilerplate, with
Fragments/layouts generated per screen instead of "Hello World".

## Where this is deliberately simple (next steps if you want to push further)
- Layout uses `FrameLayout` + margins for absolute positioning, matching the
  sketch canvas 1:1. A real generator would probably move to `ConstraintLayout`
  with proper constraints between siblings, or infer a `LinearLayout` when
  parts are neatly stacked.
- One `PartKind` → one hardcoded XML snippet. Fine for scaffolding; for
  richer output you'd swap this step for a call to the Claude API using the
  same prompt `PromptGenerator` already builds, and write *that* code into
  the template instead of the hand-written XML.
- Onboarding's "completing action" is just the first `MaterialButton` found
  on that screen. If a sketch's Onboarding has no button, nothing auto-wires
  it - see the `TODO` in the generated `OnboardingFragment`.
- No Compose path yet — everything is classic View/XML to match the
  boilerplate's style. A Compose boilerplate would need a parallel generator.
