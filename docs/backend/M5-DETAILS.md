# M5 mail actions

M5 is partial. No live Tuta request was sent. No production dependency was added.

## Contracts

`MailRepository` keeps these signatures:

```kotlin
suspend fun setUnread(account: AccountId, ids: List<MailId>, unread: Boolean): Outcome<Unit>
suspend fun move(account: AccountId, ids: List<MailId>, target: FolderId): Outcome<Unit>
suspend fun setLabels(account: AccountId, ids: List<MailId>, labels: List<LabelId>): Outcome<Unit>
```

It adds these signatures:

```kotlin
suspend fun currentFolders(account: AccountId, ids: List<MailId>): Outcome<Map<MailId, FolderId>>
suspend fun delete(account: AccountId, ids: List<MailId>): Outcome<Unit>
```

1. `setLabels` replaces the labels on each selected mail. It does not label a conversation. An empty list removes all labels. Five labels per mail is the limit. Unknown labels fail before a write.
2. `move` accepts an exact folder ID. Inbox, Archive, Trash, Spam, and custom folders use the same path. Label IDs are rejected. The SDK's `trash_mails` and `archive_mails` choose a folder by type in each mailbox. This contract needs the exact target for undo, so it uses MoveMailService instead. Read state reuses `set_unread_status_for_mails`.
3. Before a move, call `currentFolders`. Retain the returned map. To undo, group the mail IDs by their original folder and call `move` for each group. This map is a server snapshot, not a lock. A concurrent client can move a mail after the snapshot.
4. `delete` accepts only mails directly in Trash or Spam. It checks every selected mail before writing. The native method checks membership again and sends the current folder as the server filter. The official client also permits descendants of Trash and Spam. Velin deliberately rejects descendants until that policy is needed.
5. Empty selections succeed without a write. Duplicate mail IDs are removed. Account sessions remain mandatory at the source boundary.
6. Known pages and conversations change only after all requests succeed. Move removes selected mails from other cached folders. A cached destination receives known summaries. Read state updates summaries and open messages. Labels update summaries and the conversation label union. Delete removes cached summaries, bodies, consent, and messages. The next explicit sync reloads cached pages and active conversations from the server.
7. Multiple requests are not atomic on the server. A later request can fail after an earlier request succeeds. The repository returns a typed failure and leaves local action state unchanged. Sync is required to reconcile such a partial result. Cancellation can also occur after a server write. There is no automatic retry or rollback.
8. Memory caches remain session-only. Event replay and durable storage remain M4.

## Release protocol evidence

All links use release commit `332e57461cdc4fe4a4ad9bdc8555f56df02931c0`.

1. [Move and simple move](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/worker/facades/lazy/MailFacade.ts#L598): MoveMailService POST takes `mails`, `targetFolder`, `excludeMailSet: null`, and `moveReason: null`. Group by mailbag, then split at 50. Move uses individual mail IDs, with no conversation expansion.
2. [Read state](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/tuta-sdk/rust/sdk/src/mail_facade.rs#L188): UnreadMailStateService POST takes `mails` and `unread`, in batches of 50.
3. [Label assignment](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/worker/facades/lazy/MailFacade.ts#L1430): ApplyLabelService POST takes `mails`, `addedLabels`, and `removedLabels`. [The model groups by mailbag and splits at 50](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/mail-app/mail/model/MailModel.ts#L445). Velin computes each mail's difference before writing, then groups equal changes. [Five labels per mail](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/platform-kit/app-env/TutanotaConstants.ts#L489) is independent of the free-account label creation limit.
4. [Permanent delete](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/worker/facades/lazy/MailFacade.ts#L657): MailService DELETE takes `mails` and nullable `folder`. Group by mailbag, then split at 50. Velin always supplies the folder filter. [The official client checks Trash, Spam, and their descendants](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/mail-app/mail/view/MailViewModel.ts#L870).
5. Generated release models and service descriptors supply numeric field IDs, service paths, and model versions. New request records have no aggregate child objects. No aggregate IDs are needed for these payloads.

## Spam and phishing reporting: NOT IMPLEMENTED

Moving to Spam is implemented. It does not submit a report or mark a mail as phishing.

[The official spam path](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/mail-app/mail/view/MailGuiUtils.ts#L300) moves first. It checks the reporting preference or asks for consent. It waits for the undo period, skips reporting after undo, and excludes Tuta team mail. [ReportMailService POST](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/common/api/worker/facades/lazy/MailFacade.ts#L647) receives `mailId`, the resolved plaintext `mailSessionKey`, and `reportType`. [The report types](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/entities/tutanota/Utils.ts#L220) are phishing 0 and spam 1.

[The official phishing path](https://github.com/tutao/tutanota/blob/332e57461cdc4fe4a4ad9bdc8555f56df02931c0/src/applications/mail-app/mail/view/MailViewerViewModel.ts#L630) also writes the suspicious phishing status before moving and reporting. It does not offer undo.

Velin has no report-consent/preference contract, delayed-report lifecycle, or native report operation that keeps the resolved key inside Rust. Reporting needs those pieces before it can match the release. Ordinary `move` must remain reversible and must not disclose a session key. No mail key or content is sent to a reporting endpoint by this implementation. Do not present a Spam move as a submitted report. Phishing status updates and report submission remain unimplemented.

## Owner live checks: UNVERIFIED

Use a test account and compare each result in the official client.

1. Select more than 50 mails. Mark read, then unread. Check every mail in both clients. Reopen a conversation and check read state.
2. Move individual mails to Inbox, Archive, Trash, Spam, and a custom folder. Check list removal and the server folder. Check that a different mail in the same conversation stays put.
3. Save `currentFolders` before a mixed-folder move. Undo by original folder. Check each mail returns to its original folder. Confirm moving to Spam submits no report.
4. Assign different starting labels to two mails. Replace both with one label. Remove all labels with an empty list. Check each mail and the conversation label union. Check rejection above five labels.
5. Try permanent delete from Inbox and a custom folder. Check rejection and no server delete request. Delete test mails directly from Trash and Spam. Check their absence in both clients. Move a selected mail out with a second client before deletion. Check the folder filter prevents deleting it from its new folder.
6. Disable the network before each action. Check typed failure and unchanged local state. Restore the network and sync. Check server truth replaces the cached pages and active conversation.
7. For a selection above 50, fail a later request. Check failure, unchanged local action state, and correct reconciliation after sync. Do not assume server rollback.
8. Switch accounts and sign out after actions. Check no cached mail or label crosses sessions.

## Dependency review

The unchanged lockfile scan returned 327 public Rust packages, 17 affected packages, and 35 advisory records. The result exactly matches `release-native-advisories.json`, including its package versions, OSV IDs, aliases, severity fields, affected ranges, and fixed events. Those existing release risks remain. The 14 public Maven packages in `m3c-maven-packages.json` returned no advisory. No dependency update was made.

## Offline verification

1. The native rebuild passed for arm64-v8a, x86_64, and the host. `m5-artifacts.sha256` records the three libraries. Binding generation passed. Optional ktlint formatting was unavailable. Existing Rust warnings remain.
2. `tools/backend/test-read-overlay.sh` passed all 16 matching tests. These include numeric action payloads, ID fields, batch bounds, mailbag separation, and the existing read/crypto checks. The other 304 upstream unit tests were filtered out by this command.
3. `cargo test --locked -p tuta-sdk mail_facade::tests` passed 9 additional upstream tests. These verify unread, trash, and archive request construction, deduplication, and batching. Upstream integration tests were filtered out. No live service test ran.
4. `./gradlew testDebugUnitTest :core:domain:test :core:html:test assembleDebug --console=plain --max-workers=2` passed all 295 tasks. The JVM reports contain 114 tests, zero failures, and zero errors. New tests cover each action's batching, per-mail label differences, five-label rejection, unknown folder membership, Trash/Spam delete checks, unchanged state after failure, immediate cache updates, undo, and sync reconciliation.
5. The release pin check and `git diff --check` passed. Local code review covered the changed code, callers, contracts, and new files. It found and corrected an uncaught missing-folder case before the final test run.

Device behavior and server acceptance of all new mutations remain UNVERIFIED. M5 is not complete until live checks pass and reporting is implemented.
