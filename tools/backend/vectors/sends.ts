import { createPrivateKey, createPublicKey, sign, verify } from 'node:crypto'
import { readFileSync } from 'node:fs'
import assert from 'node:assert/strict'
import { TestRandomizer, resolver } from './drafts'
import { Aes, AesCbcFacade, AeadFacade, KeyEncryption, SymmetricCipherFacade, SymmetricCipherUtils,
  SymmetricKeyDeriver, SymmetricEncryptionScheme, uint8ArrayToKey, keyToUint8Array, hexToRsaPublicKey, rsaEncrypt } from '@tutao/crypto'
import { PublicKeySignatureFacade } from '@release/src/platform-kit/base/base-crypto/PublicKeySignatureFacade'
import { InstancePipeline } from '@release/src/platform-kit/instance-pipeline/InstancePipeline'
import * as types from '@tutao/entities/tutanota'
import compatibility from '@release/test/tests/api/worker/crypto/CompatibilityTestData.json'

const hex = (value: Uint8Array) => Buffer.from(value).toString('hex')
const publicKeyHex = compatibility.rsaEncryptionTests[0].publicKey
const publicKey = hexToRsaPublicKey(publicKeyHex)
const signingKey = createPrivateKey({ key: Buffer.concat([Buffer.from('302e020100300506032b657004220420', 'hex'), Buffer.alloc(32, 7)]), format: 'der', type: 'pkcs8' })
const identityKey = createPublicKey(signingKey)
// Run the release canonicalizer. Node supplies the real Ed25519 primitive without a WASM build.
const canonical = PublicKeySignatureFacade.prototype.serializePublicKeyForSigning({ object: publicKey, version: 0 }).encodedKeyPairForSigning
const signature = sign(null, canonical, signingKey)
assert(verify(null, canonical, identityKey, signature))
const vectors = []
for (const scheme of [SymmetricEncryptionScheme.AesCbc, SymmetricEncryptionScheme.Aead]) {
  for (const legacy of [false, true]) {
    const seed = `velin-send-${scheme}-${legacy}-v1`
    const random = new TestRandomizer(seed)
    const utils = new SymmetricCipherUtils(random)
    const cipher = new SymmetricCipherFacade(new AesCbcFacade(), new AeadFacade(utils), new SymmetricKeyDeriver(), utils)
    const keyEncryption = new KeyEncryption(cipher, new Aes(cipher))
    const bucket = uint8ArrayToKey(new Uint8Array(32).fill(11))
    const session = uint8ArrayToKey(new Uint8Array(legacy ? 16 : 32).fill(19))
    const wrapped = keyEncryption.encryptKey(bucket, session)
    const encryptedBucket = rsaEncrypt(publicKey, keyToUint8Array(bucket), random.generateRandomData(32))
    const recipient = types.createInternalRecipientKeyData({ mailAddress: 'self@tuta.com', pubEncBucketKey: encryptedBucket,
      recipientKeyVersion: '0', senderKeyVersion: null, protocolVersion: '0' })
    const parameters = { language: 'en', mail: ['test-mail-list', 'test-draft'], mailSessionKey: null,
      bucketEncMailSessionKey: wrapped, senderNameUnencrypted: null, plaintext: false, calendarMethod: false,
      sessionEncEncryptionAuthStatus: null, internalRecipientKeyData: [recipient], secureExternalRecipientKeyData: [],
      symEncInternalRecipientKeyData: [], attachmentKeyData: [] }
    const data = types.createSendDraftData({ ...parameters, parameters: types.createSendDraftParameters(parameters), sendAt: null, allowUndo: false })
    const pipeline = new InstancePipeline(resolver, () => { throw new Error('Unexpected key load') }, cipher,
      { getDefaultSymmetricEncryptionScheme: () => scheme }, () => { throw new Error('Unexpected instance key') }, random, keyEncryption)
    const wire = await pipeline.mapAndEncrypt(types.SendDraftDataTypeRef, data, null)
    vectors.push({ seed, legacy, scheme: scheme === SymmetricEncryptionScheme.AesCbc ? 'aes-cbc' : 'aead',
      bucketKeyHex: hex(keyToUint8Array(bucket)), sessionKeyHex: hex(keyToUint8Array(session)), publicKeyHex,
      wrappedSessionKeyHex: hex(wrapped), encryptedBucketHex: hex(encryptedBucket),
      identityKeyHex: hex(identityKey.export({ format: 'der', type: 'spki' }).subarray(-32)),
      canonicalHex: hex(canonical), signatureHex: hex(signature), randomCalls: random.calls,
      wireJson: wire.getJsonRepresentation() })
  }
}
export const output = { upstreamCommit: '332e57461cdc4fe4a4ad9bdc8555f56df02931c0', vectors }
