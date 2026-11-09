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
   store. Modern keys use current version 7 for create and historical version 3
   for update. Legacy owner keys use version 0. Historical legacy drafts
   retain a 128-bit session key while the current group key is 256-bit version 7. `keyCalls` records this selection.
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

All eight draft vectors use `WithSessionKey`. These fixtures do not cover `WithInstanceKey`,
attachment upload, reply construction, recipient key lookup, or send.
They verify offline protocol construction. Server acceptance remains unverified.

The crypto barrel also exports Ed25519. This program does not use it.
Tree shaking removes that unbuilt WebAssembly import. The runner rejects
any remaining non-Node import. It does not replace crypto with a stub.
`fetch` is disabled, and no HTTP client is supplied.

The same command also writes twelve HTML conversion cases to
`core/html/src/test/resources/draft-html.tsv`. Each line contains a base64
UTF-8 input and output, separated by a tab. Each value starts with `b64:`
so empty strings do not create trailing whitespace. Kotlin tests read this file.
The draft update vectors include repeated text to check exact compression.
Rust also regenerates aggregate IDs from null with the full injected random
stream. The result must still match each complete `wireJson` string.

Legacy key wrapping follows the release's fixed-IV AES-128 key-wrap format.
It consumes no random bytes. Attribute encryption still uses fresh IVs or
nonces. AEAD derives 256-bit subkeys from historical 128-bit session keys,
as in `src/platform-kit/crypto/encryption/symmetric/SymmetricKeyDeriver.ts:178`.
New drafts always use a 256-bit session key.

`draftUpdateReturnWire` is generated and decrypted by the release pipeline
with no session key. The type's legacy encrypted marker does not imply
ciphertext: its only fields are `_format` and attachment ID references.
The Rust decoder accepts this exact ID-only model. New encrypted fields or
aggregate fields disable that case and require key resolution.

## M6b send vectors

The same command also writes `sends.json`. Four fixtures cover a 256-bit
bucket key, 128/256-bit draft session keys, and both account schemes.
The release supplies RSA encryption, key wrapping, signature canonicalization,
SendDraftData constructors, model mapping, aggregate IDs, and wire serialization.
Node supplies real Ed25519 signing. No crypto function is replaced with a stub.

Rust checks every wire byte and the RSA and wrapping ciphertexts. It verifies
the Ed25519 signature over the release's canonical key bytes. These fixtures
cover signed RSA recipients. They do not cover deterministic TutaCrypt
encapsulation or the release Ed25519 WASM. See `docs/backend/M6B-REPORT.md`
for the native random-source limit and remaining live checks.
