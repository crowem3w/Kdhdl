# Sketch → real project, wired up

What changed from the sketch-to-prompt version:

1. **`app/src/main/assets/boilerplate/`** — your `init.zip` Hello World project,
   copied in verbatim. This is the template every export starts from.

2. **`CodeGenerator.kt`** (new) — the template-fill engine:
   - Copies `assets/boilerplate/` into a working directory.
   - Overwrites only the 4 files that depend on the sketch:
     `activity_main.xml`, `strings.xml`, `app/build.gradle.kts` (adds the
     Material dependency), `themes.xml` (switches to Material3).
   - Leaves the Gradle wrapper config, manifest, and `MainActivity.onCreate`
     from the template untouched.
   - Zips the result to `generated_app.zip` in the app's cache dir.

3. **`SketchActivity.kt` / `SketchDialogs.kt`** — the tools sheet now has an
   "Export project (.zip)" button next to "Generate prompt". Tapping it runs
   the generator and opens the share sheet (via `FileProvider`) so the zip
   can be saved to Drive, sent to a computer, etc.

4. **`file_paths.xml` + `AndroidManifest.xml`** — the FileProvider plumbing
   required to share a file from cache dir on modern Android.

## Try it
Unzip the output the app produces, then in that folder:
```
./gradlew assembleDebug
```
It's a real, complete Gradle project — same shape as the boilerplate, just
with a generated `activity_main.xml` instead of "Hello World".

## Where this is deliberately simple (next steps if you want to push further)
- Layout uses `FrameLayout` + margins for absolute positioning, matching the
  sketch canvas 1:1. A real generator would probably move to `ConstraintLayout`
  with proper constraints between siblings, or infer a `LinearLayout` when
  parts are neatly stacked.
- One `PartKind` → one hardcoded XML snippet. Fine for scaffolding; for
  richer output you'd swap this step for a call to the Claude API using the
  same prompt `PromptGenerator` already builds, and write *that* code into
  the template instead of the hand-written XML.
- No Compose path yet — everything is classic View/XML to match the
  boilerplate's style. A Compose boilerplate would need a parallel generator.
