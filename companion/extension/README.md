# Diligent companion (browser extension)

Counts time on the focused tab while you're active (2 minutes without input counts as away),
for the sites you map to Diligent activities, and sends per-activity minutes for today to your
own Supabase project every 5 minutes. The phone picks them up on its next sync and raises the
matching activities (hrs or mins). Set-up: [docs/CLOUD.md](../../docs/CLOUD.md).

**Right-click → Send to Diligent clipboard** sends the selected text, a link, an image address
or the page (title and URL) to your own clips, which the phone (Hub → Clipboard) and the web app
see. The toolbar icon flashes ✓ when it's sent, or ! with the reason in its tooltip. With a
clipboard passphrase in the options, the text is encrypted in the browser first, exactly as the
app and the web app do.

Load it in Chrome or Edge: *Extensions → Developer mode → Load unpacked → this folder*, then
open its options.
