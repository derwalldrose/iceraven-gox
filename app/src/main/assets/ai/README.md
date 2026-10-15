# Local AI renderer

Runtime assets are bundled; no CDN fetches. Versions: marked 18.1.1 (MIT), DOMPurify 3.4.17 (Apache-2.0 OR MPL-2.0), highlight.js 11.12.0 (BSD-3-Clause). License files accompany the scripts.

`https://gox-chat.invalid/ai/renderer.html` is a synthetic origin resolved solely by the fragment's asset interceptor. All other resource requests are denied. File/content access, persistent DOM storage, popups and mixed content are disabled. There is no native JavaScript bridge. API URL/key/system prompt stay in native transport, not the DOM.

Model Markdown and raw HTML pass DOMPurify before insertion. Inline CSS, script, form controls, foreignObject and embedded browsing contexts are removed. Fenced HTML/SVG preview uses sanitized `srcdoc` in an iframe with an **empty sandbox** and independent `script-src 'none'` CSP. Links never open arbitrary native schemes. Native copy/retry actions require a user gesture and the top frame; model links using that scheme are stripped.

Conversation and composer draft use an activity-scoped ViewModel, memory-only (no private chat persisted to disk). Fragment replacement cancels the transport and preserves partial output as stopped. Process death intentionally starts a fresh chat. Native multiline composer uses the activity's existing IME insets; Enter inserts a newline and send/stop is explicit.

Streaming updates are batched at 80 ms and keep existing message rows. Only the changed message body is reparsed; the renderer follows output only when the viewport was near the bottom. Stop/cancel disconnects the real HTTP connection. Retry truncates the selected response and its downstream conversation before regenerating.

## Tests

All test content is explicitly **local fixture data**, not fabricated live API output.

- `app/src/test/ai/renderer.test.cjs`: Node + jsdom verifies Markdown headings/lists/tables, syntax highlight and copy controls, sanitizer attacks, SVG, no-script sandbox preview, incremental row identity and clearing.
- `app/src/test/java/com/gox/AiChatClientTest.kt`: CI JVM/Robolectric tests for SSE multiline/comments/DONE/EOF, null content and errors, request filtering, and a real local HTTP socket fixture proving cancellation closes blocked reads.

For the JS test, install `jsdom@26.1.0` into a scratch directory, then set `NODE_PATH=<scratch>/node_modules` and run `node app/src/test/ai/renderer.test.cjs` from the repository. This is a test dependency only and is not bundled into the APK. Android build/device interactions and the Kotlin suite still require parent CI/device verification; no local APK compile was performed.
