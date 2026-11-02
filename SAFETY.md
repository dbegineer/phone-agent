# Safety model

Principle: the model proposes, the app disposes. Model output is untrusted input.

| Risk | Control |
|---|---|
| Agent acts on its own | Runs only on a user tap or voice request. No background service, no triggers. |
| Model invents a tool | Allowlist of 15 tools. Unknown names are denied. |
| Bad or hostile arguments | Per-tool validation (ranges, https only, email/phone formats, length caps). |
| Money or credentials | Banking, payment, wallet, password, trading and settings apps and sites are denied. Checked on the model's text and again on the real app label and package. |
| USSD / dial codes | Numbers starting with `*` or `#` are denied. |
| Irreversible actions | Confirm dialog first, then a system screen where the user presses send / call / save. |
| Runaway loops | 4 tool calls per request, one launching tool ends the task. |
| Data exfiltration | No INTERNET permission. Model runs on-device. |
| Prompt injection | Phase 1 reads no external content. Phase 3 (screen control) must treat all screen text as data. |
| Mistakes after the fact | Local audit log of every request, decision and result. |

## Not covered yet
- Screen reading and tapping other apps (Phase 3) needs per-app allowlist, foreground-app checks and a hard-deny screen detector.
- The kill switch cannot interrupt a model computation in flight.
- Debug builds are signed with a debug key. Do not distribute them.
