# Spelling EN — Privacy

_Last updated: 2026-10-01_

Spelling EN is an English spelling-practice app for children. It is designed to
collect as little as possible.

## What stays on the device

- The practice **word list** you type or scan.
- The **voice speed** setting.
- Your **family code**, if you create one (see below).

These are stored only in the app's local storage on the device. They are not
sent anywhere unless you turn on sharing.

## Camera

The "Scan a word list" feature uses the camera to photograph a list of words.
Text recognition runs **entirely on the device** (offline). Photos are not
uploaded and are not kept by the app.

## Optional list sharing ("family code")

If you create a **family code** and tap **Sync now**, the app sends your
**word list** and a **timestamp** to a small sync service so the same list can
be opened on another device using the same code.

- No account, name, email, or device identifier is sent — only the words and a
  timestamp, stored under the random code you chose.
- Anyone who knows a code can read and overwrite that list, so treat the code
  like a shared password.
- The sync service is a Cloudflare Worker with Cloudflare KV storage. You can
  stop using it at any time by clearing the family code; to delete a stored
  list, overwrite it with an empty list from the app.

If you never create a family code, the app makes no network requests for your
data.

## No ads, no analytics, no tracking

The app contains no advertising SDKs, no analytics, and no third-party
trackers.

## Children's privacy

The app does not knowingly collect personal information from children. The only
data that can leave the device is the practice word list described above.

## Contact

Questions: johnboniello@gmail.com
