# Draft protocol vectors

These fixtures use release commit
`332e57461cdc4fe4a4ad9bdc8555f56df02931c0`.
All keys, addresses, IDs, and content are test data.

## Regenerate

1. Set `TUTA_RELEASE` to a new checkout location outside both repositories.
2. Clone the pinned release into that location:

   ```sh
   git clone --depth 1 --branch tutanota-release-366.261105.0 \
     https://github.com/tutao/tutanota.git "$TUTA_RELEASE"
   ```

3. Install its locked development tools without install scripts:

   ```sh
   (cd "$TUTA_RELEASE" && npm ci --ignore-scripts)
   ```

4. From the Velin root, run:

   ```sh
   TUTA_RELEASE="$TUTA_RELEASE" node tools/backend/vectors/run.mjs
   git diff -- tools/backend/vectors/drafts.json
   ```

Use Node 26 and npm 10. The runner checks HEAD and rejects tracked edits.
It uses the release's locked Rolldown package. No package enters the Android
build. The temporary bundle is removed after each run.

## Coverage

1. `InstancePipeline.mapAndEncryptForDataTransferType` selects the user's
   scheme and encrypts all nested fields. The runner uses upstream TypeModels,
   TypeRefs constructors, compression, key wrapping, and crypto code.
2. The real `KeyLoaderFacade` parameter methods run against an offline key
   store. Create uses current key version 7. Update uses historical version 3,
   while the current key remains version 7. `keyCalls` records this selection.
3. Draft data follows
   `src/applications/common/api/worker/facades/lazy/MailFacade.ts:341-427`
   and `:505-592`. Construction is explicit here. The full MailFacade needs
   unrelated native and upload services. It does not run in this generator.
4. Each result decrypts through the release's InstancePipeline. Every mapped
   field, including IDs, must match the input after a round trip.
5. `wireJson` preserves upstream JSON order and bytes. `wire` contains the
   same JSON as an object. `mappedInput` contains the plaintext numeric fields
   after upstream model mapping, including generated aggregate IDs.
6. The test randomizer uses the injection point from
   `test/tests/api/worker/crypto/CompatibilityTest.ts:150`. Its stream is
   SHA-256 of the UTF-8 seed followed by a big-endian 32-bit counter, starting
   at zero. Requests consume consecutive bytes. `randomCalls` records each
   request. `encryptionRandomOffset` is the byte offset after key wrapping
   and model mapping, before attribute encryption.

Drafts use `WithSessionKey`. These fixtures do not cover `WithInstanceKey`,
attachment upload, reply construction, recipient key lookup, or send.
They verify offline protocol construction. Server acceptance remains unverified.

The crypto barrel also exports Ed25519. This program does not use it.
Tree shaking removes that unbuilt WebAssembly import. The runner rejects
any remaining non-Node import. It does not replace crypto with a stub.
`fetch` is disabled, and no HTTP client is supplied.
