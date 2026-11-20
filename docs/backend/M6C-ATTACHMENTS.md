# M6c attachments out

Protocol source: release `tutanota-release-366.261105.0`, commit
`332e57461cdc4fe4a4ad9bdc8555f56df02931c0`. No live Tuta request ran.
No mail was sent. No production dependency was added.

Velin can now attach files in the composer, forward a mail with its files,
and send confidential mail with files to Tuta addresses.

## Contracts

`MailRepository.saveDraft` now returns the saved draft with all its files:

```kotlin
suspend fun saveDraft(account: AccountId, draft: Draft): Outcome<SavedDraft>
data class SavedDraft(val id: DraftId, val attachments: List<Attachment>)
```

1. `Draft.newAttachments` holds picked files. The next save uploads them.
   After the save, they are in `SavedDraft.attachments` with server IDs.
   The composer then saves the full list, so a later save does not remove them.
2. `prepareResponse` returns a `Draft`. A forward carries every file of the
   forwarded mail in `Draft.attachments`. `PreparedResponse` and the
   "cannot forward" message are removed.
3. Until a forward draft exists, its files belong to the forwarded mail.
   The first save copies them. Later saves keep the copies.
4. `Failure.DraftPersistence.saved` is set when the server saved the draft but
   the local send block was not saved. The composer still takes the new file IDs.

## Protocol

1. Upload follows `MailFacade.ts:426`. Each new file gets a fresh 256-bit
   session key. The release SDK's `encrypt_and_upload_single_legacy` encrypts
   10 MiB chunks with AES-CBC and HMAC, as `BlobFacade.ts:234` does, and
   uploads them with an Attachments write token for the sender's mail group.
2. The `DraftAttachment` follows `MailFacade.ts:462`. `newFile.file` is a
   `FileTransferAggregatedType`. Its `_ownerEncSessionKey` is the file key
   wrapped with the draft's owner key: the current key on create, the draft's
   historical key on update. The deprecated `enc*` fields stay null.
3. A forwarded file follows `MailFacade.ts:721`. Velin resolves the file key
   through the forwarded mail's bucket key and wraps it again with the current
   owner key. It sends `existingFile` and `ownerEncFileSessionKey`.
4. Send follows `MailFacade.ts:766`. Each draft file adds an
   `AttachmentKeyData` with the file key encrypted with the bucket key, in the
   top-level data and in `parameters`.
5. The draft readback now expects the kept files plus one new file per upload
   or forward. It then reads the new files and returns them.
6. One source change: the release's chunk iterator in `blob_facade.rs` gets a
   `Send` bound. UniFFI futures must be `Send`. The bytes do not change.

## Limits

1. All files of one mail together: 25 MiB, as `MAX_ATTACHMENT_SIZE` upstream.
   One mail: 100 files. The composer refuses larger picks.
2. A file name has 1 to 255 characters and no control characters. A MIME type
   matches the upstream pattern without parameters. Other types become
   `application/octet-stream` in the picker.
3. Velin holds picked files in memory until the upload ends.
4. A lost response after a successful upload can upload the file again on the
   next save. The draft then has the file twice. Remove one copy by hand.
5. Files cannot be removed while a save uploads them. The composer shows a
   progress bar meanwhile.
6. Inline images in the quote are not carried. A forward attaches them as
   ordinary files.
7. Scheduled send and external recipients remain unsupported.

## Offline verification

1. Two new upstream vectors in `tools/backend/vectors/drafts.json`
   (`attachmentVectors`) cover a forward draft with one new file and one
   forwarded file, for AES-CBC and AEAD. Rust matches every wire byte.
   The eight earlier draft vectors did not change.
2. `tools/backend/test-read-overlay.sh` passed all 67 selected Rust tests.
   New tests cover the attachment shape, MIME validation, forwarded key
   wrapping, input limits, and file keys in send.
3. The Gradle unit tests passed: 231 JVM tests, no failures.
   `:app:assembleDebug` passed. `m6c-artifacts.sha256` records the libraries.

## Owner live checks: UNVERIFIED

Run only after merge and separate owner authorization.

1. Attach a PDF and an image. Send the mail to your own Tuta address.
   Open both files in the official client.
2. Forward a received mail with files. Check that the files open in the
   official client.
3. Reopen a draft with files after an app restart. Remove one file, save, and
   check the draft in the official client.
4. Try a 26 MB file. Check the message and that nothing was uploaded.
