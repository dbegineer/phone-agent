# phone-agent

A safety-first, fully on-device AI agent for Android. It understands a request ("set an alarm for 7", "open the camera", "play some jazz"), picks one tool from a fixed allowlist, and asks you before doing anything that is not read-only.

**Status: Phase 1.** Voice or text in, on-device Gemma, 15 tools, confirmation screen, kill switch, audit log.

## Design rules
- **No INTERNET permission.** The app physically cannot send data off the phone.
- **User-initiated only.** Nothing runs unless you tap Send or speak.
- **Allowlist.** The model can only call the 15 tools in `core/.../Tools.kt`.
- **Every call goes through `SafetyGate`** (`core/.../Safety.kt`): unknown tools, bad arguments, banking/payment/password/settings targets, non-https links and USSD codes are denied.
- **Confirm before acting.** Anything that is not read-only shows an "Allow once / Cancel" dialog, then hands off to a system screen where you press the final button (send, call, save, shutter).
- **Kill switch:** the red STOP button. Step cap: 4 per request.
- **Audit log** at `files/audit.log` inside the app, never uploaded.

See [SAFETY.md](SAFETY.md) for the full threat list.

## Get the APK (no computer needed)
1. Open the **Actions** tab on GitHub, pick the latest **Build APK** run.
2. Download the **phone-agent-debug-apk** artifact (a zip), unzip, open `app-debug.apk`, and allow installs from your browser or Files app.
3. If the build is red, open the run, copy the error from the failed step, and send it to Claude.

## Get a model
Download a `.litertlm` model into your phone's Downloads, for example Gemma E2B from the LiteRT Community on Hugging Face (`litert-community`). Some models need you to accept a license first. In the app tap **Pick model** and choose the file. It is copied into the app and loaded.

## Layout
- `core/` pure Kotlin: tool list, `SafetyGate`, unit tests (run on every push)
- `app/` Android: UI, on-device model (LiteRT-LM), tool executor

## Known limits in Phase 1
- Tools that open another app end the task (Android blocks background app launches). Multi-step control of other apps comes with the accessibility service in Phase 3.
- STOP ignores the model's output but cannot interrupt a computation already in progress.
- Audit log is capped, not rotated.
