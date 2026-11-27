# The Structure — Personal Voice Assistant (Android)

Your own voice assistant, on your phone, fully offline. Say **"The Structure"**
anywhere — lock screen, home screen, inside any app, screen off — and it
wakes, checks it is really your voice, asks for your passcode, then does
what you say. The brain is Gemma 3 4B Abliterated v2 running on the phone
itself. No accounts, no subscription, no expiry. An optional free Groq key
("Jarvis mode", Settings) makes chat answer in seconds; without it the
on-device brain answers.

**v1.4.3 — what changed:** chat and voice are one record now, and the
voice panel floats over apps. Every spoken command and its result are
written into Chat, so the chat history shows what the voice assistant
did — open, call, answer, everything. The voice panel is now a small
floating card over whatever app he is using (the "Display over other
apps" permission), with the mic, what it heard, and the result — no more
full-screen takeover; the full-screen screen stays only as a fallback
when the overlay permission is off. This build also includes the v1.4.2
chat–voice connection (brain knows it can control the phone, and the
"Cheppandi" prompt is no longer spoken aloud). Same package and signing
key, so v1.4.3 installs as an update — no uninstall, no setup redo.

**v1.4.2 — what changed:** chat and voice are finally connected. The cloud
brain did not know what this app can do, so it told him it cannot control
the phone — now the Jarvis prompt states the phone-control powers, and
"can you control my phone?" gets the true answer on the phone itself.
Also, the "Cheppandi" prompt is no longer spoken aloud: the voice-command
screen already shows it, and speaking it only fed the mic its own echo.
Same package and signing key, so v1.4.2 installs as an update — no
uninstall, no setup redo.

**v1.4.1 — what changed:** the mic screen stopped closing on its own
echo. The app speaks "Cheppandi" and the mic hears its own voice — Vosk
took that for his command and the session ended at once, about two seconds
after the screen appeared. Listening now ignores anything recognised in
the first two seconds, and the voice-command screen only closes after it
has actually seen the session listening. Same package and signing key, so
v1.4.1 installs as an update — no uninstall, no setup redo.

**v1.4 — what changed:** the voice lock stopped rejecting him. The wake
utterance is short, so it scores lower than the long enrolment samples —
the default threshold is now **0.55** (was 0.65) and the Settings slider
goes down to **0.40**. A near-miss voice (score 0.40+) no longer dies
silently: it falls back to the passcode, so the voiceprint can never lock
him out of his own phone — and Settings now shows the score behind every
rejection ("voice_rejected (0.58/0.55)"). After the passcode, a
voice-command screen opens (big mic, "Vinipisthunnanu… Cheppandi, Guruvu
garu", live heard text, result) so he SEES the app listening. Typed chat
now runs through the CommandEngine first: "open youtube" OPENS YouTube
instead of asking the brain for a link. And the app icon is a bold gold
**"S" monogram** — the temple/house mark is gone. Same package and
signing key, so v1.4 installs as an update — no uninstall, no setup redo.

**v1.3 — what changed:** the app is now **The Structure** and the wake word
is **"The Structure"** too. "The" is a weak word that speech recognition
often mumbles or drops, so the app also answers to **"da structure"** and
the bare word **"structure"**; **Full listening** is now the default wake
mode (recommended). "Structure" is a common word, so the phone may wake on
other speech too — the voice check and passcode still protect it. Your
voiceprint does **not** need re-enrollment — it checks your voice, not the
words. With a Groq key saved, the 2.8 GB local model is no longer loaded
when chat opens (it used to run the phone out of memory and close the app);
it loads lazily only if Groq fails. The interface got a polish too: a
darker, layered theme with the gold accent, rounded cards, a new
Structure-mark app icon, and the app mark in the Chat/Setup/Settings
headers. The repo was renamed to **the-structure-** on GitHub; the package
name (`com.heygobi.app`) and signing key are unchanged, so v1.3 installs
as an update — no uninstall, no setup redo.

**Phone:** Realme Narzo 80 Pro, 8 GB RAM, Android 16
**Package:** `com.heygobi.app`

---

## How it works (the short version)

| Piece | What it does |
|---|---|
| Wake word | Vosk (small English model, ~40 MB) listens for "The Structure" (also "da structure" and the bare word "structure" — "the" is often mumbled or dropped) |
| Voice lock | WeSpeaker ECAPA-TDNN voiceprint — only YOUR voice wakes it |
| Passcode gate | 4–8 digit passcode after waking, before any phone access |
| Brain | llama.cpp + Gemma 3 4B Abliterated v2 Q5_K_M (2.8 GB, on-device) |
| Phone control | AccessibilityService: tap, scroll, type, open apps, unlock |
| Notifications | Notification listener: reads other apps' notifications aloud, replies by voice (WhatsApp etc.) |
| Display over apps | Suggestion bar over any app while voice typing |
| Battery | REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — Setup asks Android to leave the listener alone |
| Screen-lock unlock | Your PIN/password/pattern, stored encrypted, entered by the service |
| Voice | System TTS (Telugu preferred) — fast commands never wait on the brain |

**Two speeds:** simple commands (open app, call, type, scroll, routines)
run on a fast local path and answer instantly. Chat answers go to Groq
first when a key is saved (Jarvis mode, seconds); otherwise to the
Gemma brain, which is slow on this phone (a few seconds per reply) —
that is an honest limit of a 4 GB model on a phone CPU.

---

## Push to GitHub (Termux, like the Altius flow)

Do these in Termux, one command at a time. The zip below contains the
whole project. The repo is `bakenekowild-oss/the-structure-` on GitHub
(renamed from hey-gobi-app).

```bash
cd ~
unzip ~/storage/downloads/hey-gobi-app-v1.zip -d hey-gobi-app
```

If you already have a repo for this app, clone it first instead and copy
the files in:

```bash
git clone https://github.com/bakenekowild-oss/the-structure-.git hey-gobi-app
```

Then, inside the project folder:

```bash
cd ~/hey-gobi-app
git add .
git commit -m "The Structure v1 - full app"
git push origin main
```

**First push to a brand-new empty repo** (create it on github.com first,
empty, no README):

```bash
cd ~/hey-gobi-app
git init
git branch -M main
git remote add origin https://github.com/bakenekowild-oss/the-structure-.git
git add .
git commit -m "The Structure v1 - full app"
git push -u origin main
```

GitHub Actions then builds the debug APK automatically (5–15 minutes,
the llama.cpp native build is the slow part). Download it from the
repo's **Actions** tab → the finished run → **Artifacts** →
`hey-gobi-app-debug-apk`.

**What is NOT in the repo:** the 2.8 GB model (never bundled in the APK
either), `local.properties`, keystores, build output. See `.gitignore`.

---

## First-run setup (on the phone, in order)

1. **Install the APK** from the Actions artifact. Allow "Install unknown apps" if asked.
2. **Copy the brain model to Downloads** (Termux on the phone):
   ```bash
   cp ~/ai/models/gemma-3-4b-it-abliterated-v2.q5_k_m.gguf ~/storage/downloads/
   ```
3. Open **The Structure → Setup tab** and work down the list:
   1. **Permissions** — microphone, notifications, contacts, calls, SMS, location.
   2. **Battery — Unrestricted.** This is the step that matters most:
      without it Android kills the listener, exactly like the Termux
      server kept dying.
   3. **Accessibility service** — Settings → Accessibility → The Structure control → On.
   4. **Brain model** — tap "Import model", pick the .gguf from Downloads,
      then "Load brain (test it)". Takes a minute; the file is 2.8 GB.
   5. **Voice spotter model** — downloads Vosk (~40 MB, internet once).
   6. **Voiceprint model** — downloads WeSpeaker (~25 MB, internet once,
      SHA-256 verified).
   7. **Voice enrollment** — say "The Structure" 3 times, clearly, in a quiet room.
   8. **App passcode** — 4–8 digits.
   9. **Screen-lock credential** (recommended) — your phone's own PIN /
      password / pattern. Stored encrypted on-device only, never sent
      anywhere. Without it, unlock the phone by hand when it wakes.
   10. **Emergency contact** (optional) — saying "Help" calls this person at once.
   11. **Notification access** (optional, recommended) — lets The Structure
       read your notifications aloud and reply in WhatsApp etc. by voice.
       Android shows a warning when enabling it; that is normal.
   12. **Display over other apps** (optional) — suggestion bar over any
       app while voice typing. Without it, suggestions are voice-only.
4. Tap **"Start listening"**, then **"Mark setup done"** (so the listener
   restarts itself after a reboot).

After that, everything is offline. Internet is needed only once for the
two small model downloads, and for weather/NJK status in routines.

---

## What to test on the phone (in priority order)

1. **Chat works** — Chat tab, type a message. The brain replies in
   Tanglish. If it fails, re-check step 4 above (model import).
2. **"The Structure" wakes it** — lock the phone, say "The Structure". The passcode
   screen should appear. If it never wakes, go to Settings → wake mode →
   **Full listening** and try again (the word "the" is often mumbled or
   dropped in speech — the app also answers to "da structure" and the
   bare word "structure" — and full listening is the recommended mode).
3. **Voice lock blocks strangers** — have someone else say "The Structure".
   Nothing should happen: no passcode screen, no sound. If it wakes for
   them, raise the voice threshold in Settings (0.65 → 0.75).
4. **Passcode gate** — enter the right passcode: the phone unlocks (if
   you saved the lock credential) and the app listens for one command.
   Enter a wrong one 5 times: it locks for 60 seconds.
5. **"WhatsApp open cheyyi"** — the app opens WhatsApp.
6. **"Type cheyyi"** — open any text field first, then say what to type.
7. **"Help"** — calls your emergency contact at once (warn them first).
8. **"Good morning"** — reads the routine (weather needs internet).
9. **"Idi enti?"** — with any app open, it reads the screen back.
10. **Screen off** — turn the screen off, wait a minute, say "The Structure".
    It should still wake. If it stops waking after a while, re-check
    battery Unrestricted (step 2 above).
11. **Reboot** — after a restart, the listener should come back on its own
    (only if you tapped "Mark setup done").
12. **Notifications** — send yourself a WhatsApp message from another
    phone, then say "The Structure" → "Notifications chaduvu". It should read
    it aloud. Then "WhatsApp lo reply hello" — the reply should arrive.

---

## Voice commands (fast local path — no brain wait)

| Say | What happens |
|---|---|
| "The Structure" | Wake (then voice check, then passcode) |
| "WhatsApp open cheyyi" / "<app> open cheyyi" | Opens the app |
| "Type cheyyi" | Types what you dictate, with suggestions |
| "Call Raju" / "Message Raju ..." | Reads back, sends/calls after your "yes" |
| "WhatsApp lo Raju ki ..." | WhatsApp message, same confirmation |
| "Notifications chaduvu" / "Ye messages vachayi" | Reads the latest notifications aloud |
| "WhatsApp lo Raju ki reply hello" | Replies to Raju's WhatsApp message by voice |
| "Reply hello" | Replies to the latest replyable notification |
| "Idi enti?" | Reads the current screen aloud |
| "Good morning" | Routine: weather + schedule + NJK status |
| "Work mode" | Silences the phone, opens the SAB checklist |
| "Help" | Calls your emergency contact at once |
| "Back" / "Home" / "Lock" | Navigation / lock the phone |
| "Scroll cheyyi" / "Scroll up" | Scrolls the screen |
| "Volume tagginchu" / "Silent cheyyi" | Volume / do-not-disturb |
| "Time enti?" / "Weather enti?" | Time, date, weather (weather needs internet) |
| "Routine create cheyyi" | Builds a routine by voice, step by step |
| Anything else | Falls back to the Gemma brain for an answer |

Chat (in the app) goes to the brain for everything.

---

## Honest limits (read this before judging it)

- **Notification access needs a manual enable.** Setup step 11 (or
  Settings) opens the Android notification-access screen; the warning
  there is normal. Replies use the notification's own reply button, so
  they work where the messaging app offers one (WhatsApp, Telegram,
  SMS apps). Notifications are kept in memory only — never saved,
  never uploaded.
- **The brain is slow.** Gemma 3 4B on a phone CPU answers in a few
  seconds per reply. Voice commands never wait on it; only chat does.
- **Speech recognition is English (Tanglish).** A Telugu speech model
  exists (vosk-model-small-te-0.42) but its published word error rate
  is ~88% — effectively unusable — so the app listens in English and
  you speak Tanglish. The word "the" is often mumbled or dropped in
  speech, so the app also answers to "da structure" and the bare word
  "structure". Full listening is the recommended wake mode; grammar mode
  listens for "the structure", "da structure" and "structure".
- **Voice verification is similarity matching, not a security proof.**
  A very good recording of your voice could fool it. The passcode gate
  is the real lock — that is why both exist.
- **Pattern unlock is device-dependent.** PIN and password unlock are
  the reliable paths; pattern works only if the device exposes the
  pattern view. If it fails, unlock by hand.
- **Screen-off listening needs the service running + battery
  Unrestricted.** Android may still kill it under heavy memory
  pressure — reopen the app to restart it.
- **The voice you hear is the system TTS voice**, not your cloned voice.
- **First run needs internet once** for the two small model downloads.
  Weather and NJK status in routines need internet each time.
- **The model is never in the APK.** If you uninstall the app, import
  the model again — the .gguf in Downloads stays put.

---

## Project layout

```
hey-gobi-app/
├── .github/workflows/android.yml   # GitHub Actions: builds + verifies the APK
├── app/build.gradle.kts            # AGP 8.8.2, Kotlin 2.0.21, NDK 27.2.12479018
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── cpp/
│   │   ├── CMakeLists.txt          # FetchContent llama.cpp v1.1.0 (ggml-org)
│   │   └── llama_jni.cpp           # JNI bridge (batch_ext API, no deprecated calls)
│   └── java/com/heygobi/app/
│       ├── HeyGobiApp.kt           # Application + singletons
│       ├── brain/                  # LlamaBridge (JNI), ChatRepository, SystemPrompt
│       ├── audio/                  # AudioCapture, MelFeatures, VoskManager, TtsEngine
│       ├── voice/                  # WakeWordService, CommandEngine, SpeakerVerifier,
│       │                           # VoiceEnrollment, VoiceIO, SessionState, SuggestionEngine
│       ├── notifications/          # GobiNotificationListenerService (read + reply)
│       ├── control/                # GobiAccessibilityService, ScreenLockUnlocker, AppLauncher
│       ├── security/               # SecureStore (encrypted), PasscodeManager
│       ├── service/                # BootReceiver
│       ├── data/                   # Room: chat, routines, command log
│       └── ui/                     # MainActivity, Chat/Setup/Settings screens,
│                                   # PasscodeActivity, ModelImportActivity, Theme
└── README.md
```

## Building locally (optional)

```bash
export JAVA_HOME=~/jdk-21
./gradlew assembleDebug
```

The llama.cpp native build takes 10–20 minutes on a small machine.
GitHub Actions is the canonical build path — the workflow verifies the
APK contains `lib/arm64-v8a/libheygobi.so` with llama symbols and fails
otherwise.

**Note on this VM:** the Gradle daemon cannot run in this particular
development VM (it starts, then the client-daemon handshake fails and
the daemon exits — verified across Gradle 8.2.1 and 8.11.1, all heap
sizes, both daemon modes). That is an environment limitation, not a
code problem, and it does not affect GitHub Actions. What WAS verified
here instead of a full Gradle build:
- `llama_jni.cpp` compiles clean against the real llama.cpp v1.1.0
  headers with the NDK compiler, and links into a working
  `libheygobi.so` (all 7 JNI entry points exported, llama symbols
  present) — the same code the Actions build compiles.
- All 32 Kotlin files parse with 0 syntax errors (Kotlin 2.0.20
  parser), all imports resolve to declared dependencies, all
  manifest components exist, all JNI names match Kotlin 1:1, and
  the workflow YAML parses.
- The Android SDK (platform 35, build-tools 35.0.0, NDK 27.2.12479018,
  CMake 3.22.1, platform-tools) is installed and verified working.

---

## Privacy

Everything runs on the phone. No account, no server, no analytics.
The voiceprint, passcode, screen-lock credential and chat history are
stored encrypted on-device only. Nothing is ever uploaded.
