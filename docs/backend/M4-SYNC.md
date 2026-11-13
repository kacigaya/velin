# M4 sync and offline

M4 is done. Unit tests pass, and the live checks below passed on 2026-11-12. No production dependency was added.

## Event bus

`EventBus` in `:core:network` replaces `CounterEvents`. One instance belongs to one session.

1. Each connect asks the data layer for the saved cursors. A cursor is the last applied event batch ID for one group. The socket URL sends them as `groupsToLastEventBatchIds=group=batch;…`, the same as upstream. The server then sends the missed batches, then `initialSyncDone`.
2. Velin reads only the metadata of each entity update: application, type ID, list ID, element ID and operation. It does not decrypt the `instance` data in the update. It loads changed mail again through the SDK.
3. Events go into one ordered queue with 256 places. A full queue ends the connection. The reconnect replays from the last saved cursor, so no batch is lost.
4. `unreadCounterUpdate` works as before. A counter change now reloads only the folder list, not each page.
5. The frame limit is 16 MiB. Entity updates can carry an encrypted mail details blob.

## Applying a batch

`BackendReadRepository.apply` changes the cache for one batch:

| Change | Result |
|---|---|
| Mail delete | Remove the mail from pages, conversations, bodies and summaries. |
| Mail create or update, mail known | `locate` loads the summary and current folder. The mail leaves other folders and updates in place. Draft bodies are dropped. |
| MailSetEntry in a known folder list | Reload the first page of that folder. Mails loaded after the first page stay. |
| MailSetEntry in an unknown list, MailSet, MailBox | Reload the folder and label list. |

After the changes succeed, the repository saves the cache and the new cursor in one write. If a change fails offline, the cursor does not move and the work stays for the next batch. A reconnect replays the batch. A cursor never moves back, because base64ext IDs sort by time.

Without cursors, the server sends no replay. Velin then runs a full sync when the socket opens. Upstream gives each group a start cursor from the clock. Velin does not, because it does not know the group IDs before the first batch. Cursors older than 44 days (`ENTITY_EVENT_BATCH_EXPIRE_MS`) are dropped, and a full sync follows.

## Offline store

`EncryptedOfflineStore` keeps one file per account in `noBackupFilesDir/offline/`.

1. The file name is the SHA-256 of the user ID. The content is JSON, encrypted with AES-256-GCM. The AAD contains the user ID, so a file copied to another account fails.
2. The key is the non-exportable Keystore key `app.velin.offline.v1`. It is separate from the credential key.
3. `AtomicFile` replaces the whole file on each write. A failed read or authentication deletes the file. Velin then loads from the server.
4. The file keeps folders, labels, senders, loaded pages (200 mails per folder), opened mails with their raw HTML and list entry (100 mails, 4 Mi characters in total), cursors and the last sync time. Bodies are sanitized again on each restore. External content consent is never saved.
5. Only the signed-in account writes. Sign-out and account removal delete the file.

Limit: each write rewrites the whole file. This is fast enough at the limits above. Local search (M8) needs an indexed store. Replace this file with SQLCipher then.

## Offline cold start

When a saved account resumes without network and has saved mail, the account becomes active without a session. Reads that need the server return `Failure.Offline`. The repository shows saved folders, pages and opened mails, and reports `SyncState.Offline` with the last sync time. A retry loads the session after 2 s, then doubles up to 60 s. An expired session then goes to the expired state.

## Live checks

These checks ran on Android 15 in a container, against production, on 2026-11-12. [DEVICE-TESTING.md](../DEVICE-TESTING.md) shows how to take the app offline.

1. Cold start without network shows the saved folders and inbox with the offline banner. A mail opened before shows its body. A mail never opened shows the offline error.
2. The first connect had no cursors, so the server sent no replay. A read-state change then sent two batches, and the cursor was saved.
3. While Velin was offline, the official client moved a mail from Inbox to Archive. After reconnect, the server accepted the cursors and replayed three batches. The mail left the inbox and showed in Archive with no manual refresh.
4. On 2026-11-13 a new mail arrived while the inbox list was at the top. The page reload had the mail, but the list did not show it. A keyed `LazyColumn` keeps its first visible row in place, so the new row was just above the view. Now a list at the top stays at the top when a newer mail arrives. A list scrolled further down keeps its place. After the fix, the new mail showed at the top with no scroll or refresh.
