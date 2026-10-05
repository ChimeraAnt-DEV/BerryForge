# Phase 3 tunnel notes

## What is implemented

The MCP server itself is complete and self-contained: JSON-RPC 2.0 over the
Streamable HTTP and HTTP+SSE transports, bearer authentication, approval gating,
sandbox enforcement and the eight tools. None of that depends on a tunnel. The
server binds to `127.0.0.1` and is reachable on the device with no external
service involved.

The tunnel layer is what turns that loopback endpoint into a public HTTPS URL.
Three modes are implemented:

| Mode | Mechanism | Requirements |
| --- | --- | --- |
| Cloudflare (default) | Downloads `cloudflared`, runs `tunnel --url http://127.0.0.1:<port>`, scrapes the assigned `*.trycloudflare.com` hostname from the agent's stdout | None |
| ngrok | Downloads the ngrok agent, runs `config add-authtoken` non-interactively, then `http <port> --log stdout`, and reads the public URL from ngrok's local admin API on port 4040 | A user-supplied authtoken |
| Custom | POSTs the local endpoint to a relay the user hosts and uses the `public_url` the relay returns | A relay the user runs |

Each agent binary is downloaded into app-private storage on first use rather than
bundled, which keeps the APK small and lets the user see exactly what is fetched.

## What is NOT verified here

**The tunnel has not been exercised end to end.** This build environment is a
headless container. It has:

- no Android device, so no `cloudflared`/`ngrok` arm64 binary can be executed;
- no stable public egress, so no quick-tunnel hostname can be allocated;
- no way to observe a `*.trycloudflare.com` URL from the outside, which is the
  only place it resolves.

The code paths for `startCloudflare`, `startNgrok`, `startCustom`, the output
scraper, the ngrok admin-API poller and the foreground service are written and
compile, but they have only been reasoned about, not run.

The MCP server, by contrast, has no such caveat: it is a plain loopback HTTP
server and can be tested on-device without any tunnel at all.

## How to verify on a tablet

1. Confirm the server works with no tunnel:
   ```
   adb shell curl -s http://127.0.0.1:8765/health          # expect 200
   adb shell curl -s -X POST http://127.0.0.1:8765/mcp \
     -H "Authorization: Bearer <token>" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
   ```
   Expect a `result` object with `serverInfo.name == "berryforge"`.

2. Start the Cloudflare tunnel from Settings and confirm a `trycloudflare.com`
   URL appears in the app and in the notification.

3. From a laptop:
   ```
   curl -s https://<assigned-host>/health
   curl -s -X POST https://<assigned-host>/mcp \
     -H "Authorization: Bearer <token>" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
   ```
   Expect the eight tool definitions.

4. Paste the URL and token into OpenHands and confirm the handshake and a
   `list_repos` call both succeed.

5. Repeat with ngrok and with a custom relay.

## Known rough edges to watch for

- `cloudflared` writes its assigned hostname to stdout, but the exact line varies
  between releases. The matcher is a regex for `https://<id>.trycloudflare.com`
  which has been stable, but if a future release changes the format the tunnel
  will report a 60-second timeout instead of picking up the URL. The failure mode
  is safe (no URL is displayed, nothing is exposed) but it would need the regex
  updated.
- ngrok's admin API is assumed to be on port 4040. If the user already has a
  process there, the poller will read the wrong tunnel. Worth checking on-device.
- The quick tunnel hostname is ephemeral. It changes on every start, so the URL
  must be re-pasted into OpenHands after a restart. This is inherent to quick
  tunnels, not a bug, but it is worth surfacing in the UI more prominently.
