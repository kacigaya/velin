import { plainTextToHtml } from '@release/src/applications/common/api/common/utils/imapImportUtils/PlainTextToHtmlConverter'
import { createHash } from 'node:crypto'
import assert from 'node:assert/strict'
import {
  Aes, AesCbcFacade, AeadFacade, AeadCipherVersion, KeyEncryption, Randomizer,
  SymmetricCipherFacade, SymmetricCipherUtils, SymmetricKeyDeriver,
  SymmetricEncryptionScheme, uint8ArrayToKey, keyToUint8Array,
} from '@tutao/crypto'
import { IncomingServerJson } from '@release/src/platform-kit/instance-pipeline/TypeMapper'
import { InstancePipeline } from '@release/src/platform-kit/instance-pipeline/InstancePipeline'
import { ClientModelInfo, ClientOnlyTypeModelResolver } from '@release/src/platform-kit/instance-pipeline/EntityFunctions'
import { KeyLoaderFacade } from '@release/src/platform-kit/base/base-crypto/KeyLoaderFacade'
import * as types from '@tutao/entities/tutanota'
import tutanota from '@release/src/entities/tutanota/TypeModels.js'
import sys from '@release/src/entities/sys/TypeModels.js'
import storage from '@release/src/entities/storage/TypeModels.js'
import { Mode, PaymentSetup } from '@tutao/app-env'
Object.assign(globalThis, { env: { mode: Mode.Test, paymentSetup: PaymentSetup.None, platformId: null, dist: false,
  staticUrl: null, versionNumber: '366.261105.0', timeout: 1000, domainConfigs: {}, networkDebugging: false,
  clientName: null, integrationPlatform: null, shadowDomAppRoot: null } })

// Same injection point as test/tests/api/worker/crypto/CompatibilityTest.ts:150.
// SHA-256(seed || big-endian u32 counter) supplies a reproducible byte stream.
class TestRandomizer extends Randomizer {
  private counter = 0
  private remaining = Buffer.alloc(0)
  readonly calls: number[] = []
  constructor(private readonly seed: string) { super() }
  override generateRandomData(size: number): Uint8Array {
    this.calls.push(size)
    while (this.remaining.length < size) {
      const counter = Buffer.alloc(4)
      counter.writeUInt32BE(this.counter++)
      this.remaining = Buffer.concat([this.remaining, createHash('sha256').update(this.seed).update(counter).digest()])
    }
    const result = new Uint8Array(this.remaining.subarray(0, size))
    this.remaining = this.remaining.subarray(size)
    return result
  }
}
const modelInfo = new ClientModelInfo()
for (const [app, clientModel, version] of [['tutanota', tutanota, 117], ['sys', sys, 157], ['storage', storage, 15]] as const) {
  modelInfo.registerApp({ app, clientModel, modelInfo: { version } })
}
const resolver = new ClientOnlyTypeModelResolver(modelInfo)
const hex = (b: Uint8Array) => Buffer.from(b).toString('hex')
const json = (value: unknown) => JSON.parse(JSON.stringify(value, (_key, v) => {
  if (v instanceof Uint8Array) return { bytes: hex(v) }
  return v
}))
const vectors = []
let draftUpdateReturnWire: string | undefined
for (const scheme of [SymmetricEncryptionScheme.AesCbc, SymmetricEncryptionScheme.Aead]) {
  for (const operation of ['create', 'update'] as const) {
   for (const legacy of [false, true]) {
    const seed = legacy ? `velin-draft-${scheme}-${operation}-legacy-v1` : `velin-draft-${scheme}-${operation}-v1`
    const random = new TestRandomizer(seed)
    const utils = new SymmetricCipherUtils(random)
    const cipher = new SymmetricCipherFacade(new AesCbcFacade(), new AeadFacade(utils), new SymmetricKeyDeriver(), utils)
    const keyEncryption = new KeyEncryption(cipher, new Aes(cipher))
    const sessionKey = uint8ArrayToKey(Uint8Array.from({ length: legacy && operation === 'update' ? 16 : 32 }, (_, i) => i + 32))
    const current = { object: uint8ArrayToKey(Uint8Array.from({ length: legacy && operation === 'create' ? 16 : 32 }, (_, i) => i)), version: legacy && operation === 'create' ? 0 : 7 }
    const historical = { object: uint8ArrayToKey(Uint8Array.from({ length: legacy ? 16 : 32 }, (_, i) => i + 64)), version: legacy ? 0 : 3 }
    const ownerKey = operation === 'create' ? current : historical
    const ownerEncSessionKey = keyEncryption.encryptKey(ownerKey.object, sessionKey)
    const keyCalls: string[] = []
    // Run the real parameter assembly methods with an offline key store.
    const loader = {
      keyEncryption,
      loadCurrentKeyPair: async () => { throw new Error('Unexpected asymmetric key lookup') },
      loadSymUserGroupKey: async () => { throw new Error('Unexpected user-group key lookup') },
      getCurrentSymGroupKey: async (group: string) => { keyCalls.push(`current:${group}`); return current },
      loadSymGroupKey: async (group: string, version: number) => {
        keyCalls.push(`historical:${group}:${version}`)
        assert.equal(version, historical.version)
        return historical.object
      },
    }
    const params = operation === 'create'
      ? await Reflect.apply(KeyLoaderFacade.prototype.assembleServiceEncryptionParamsForPost, loader, ['test-mail-group', AeadCipherVersion.WithSessionKey])
      : await Reflect.apply(KeyLoaderFacade.prototype.assembleServiceEncryptionParamsForPut, loader, ['test-mail-group', { _ownerKeyVersion: legacy ? null : String(historical.version) }, AeadCipherVersion.WithSessionKey])
    const address = (name: string, address: string) => types.createMailAddressTransferAggregatedType({ name, address, contact: null })
    const to = [address('Recipient λ', 'recipient@example.invalid')]
    const transferKeyVersion = legacy && operation === 'update' ? null : String(ownerKey.version)
    const draftData = types.createDraftData({
      addedAttachments: [], removedAttachments: [],
      mail: types.createMailTransferAggregatedType({
        _ownerEncSessionKey: ownerEncSessionKey, _ownerKeyVersion: transferKeyVersion, _kdfNonce: null,
        subject: 'Test <draft> λ', sender: address('Test Sender', 'sender@example.invalid'),
        confidential: true, method: '0', firstRecipient: to[0],
      }),
      mailDetailsBlob: types.createMailDetailsBlobTransferAggregatedType({
        _ownerEncSessionKey: ownerEncSessionKey, _ownerKeyVersion: transferKeyVersion, _kdfNonce: null,
        details: types.createMailDetailsTransferAggregatedType({
          body: types.createBodyTransferAggregatedType({ compressedText: operation === 'create' ? '<div>Hello &amp; goodbye λ</div>' : '<div>Repeated λ &amp; text. '.repeat(80) + '</div>', text: '' }),
          recipients: types.createRecipientsTransferAggregatedType({
            toRecipients: to, ccRecipients: [address('', 'cc@example.invalid')], bccRecipients: [],
          }),
          replyTos: [types.createEncryptedMailAddressTransferAggregatedType({ name: 'Reply', address: 'reply@example.invalid' })],
        }),
      }),
      subject: null, compressedBodyText: null, senderMailAddress: null, senderName: null,
      confidential: null, method: null, toRecipients: [], ccRecipients: [], bccRecipients: [], replyTos: [], bodyText: null,
    })
    const input = operation === 'create'
      ? types.createDraftCreateData({ previousMessageId: null, conversationType: '0', draftData, ownerEncSessionKey: null, ownerKeyVersion: null })
      : types.createDraftUpdateData({ draft: ['test-mail-list', 'test-draft'], draftData })
    const pipeline = new InstancePipeline(resolver, () => loader, cipher,
      { getDefaultSymmetricEncryptionScheme: () => scheme,
        createAuthHeaders: () => { throw new Error('Unexpected auth header creation') },
        isFullyLoggedIn: () => true,
        getLoggedInUser: () => { throw new Error('Unexpected user lookup') },
        getCurrentUserGroupKey: () => { throw new Error('Unexpected user-group key lookup') },
        setLeaderStatus: () => { throw new Error('Unexpected event bus state') },
        getUserGroupId: () => { throw new Error('Unexpected user-group ID lookup') } }, () => ({ makeInstanceKeyProvider: async () => null }), random, keyEncryption)
    if (draftUpdateReturnWire == null) {
      const response = types.createDraftUpdateReturn({ attachments: [['test-file-list', 'retained-file']] })
      const responseWire = await pipeline.mapAndEncrypt(types.DraftUpdateReturnTypeRef, response, null)
      const model = await resolver.resolveServerTypeReference(types.DraftUpdateReturnTypeRef)
      const decoded = await pipeline.decryptAndMap(IncomingServerJson.expectSingleInstance(responseWire.getJsonRepresentation(), model), null)
      assert.deepEqual(decoded.attachments, response.attachments)
      draftUpdateReturnWire = responseWire.getJsonRepresentation()
    }
    let mappedInput: unknown
    let encryptionRandomOffset = 0
    const map = pipeline.modelMapper.mapToDecryptedInstance.bind(pipeline.modelMapper)
    pipeline.modelMapper.mapToDecryptedInstance = async instance => {
      const parsed = await map(instance)
      const plain = await Reflect.apply(pipeline.typeMapper.makeServerJson, pipeline.typeMapper, [parsed])
      mappedInput = JSON.parse(plain.getJsonRepresentation())
      encryptionRandomOffset = random.calls.reduce((sum, size) => sum + size, 0)
      return parsed
    }
    const wire = await pipeline.mapAndEncryptForDataTransferType(input._type, input, params.ownerKey, params.aeadCipherVersion, params.sessionKeyDecryptor)
    const offset = encryptionRandomOffset
    const serverModel = await resolver.resolveServerTypeReference(input._type)
    const decrypted = await pipeline.decryptAndMap(IncomingServerJson.expectSingleInstance(wire.getJsonRepresentation(), serverModel), sessionKey)
    const roundTrip = await map(decrypted)
    const roundTripJson = await Reflect.apply(pipeline.typeMapper.makeServerJson, pipeline.typeMapper, [roundTrip])
    assert.deepEqual(JSON.parse(roundTripJson.getJsonRepresentation()), mappedInput)
    vectors.push({ legacy, encryptionRandomOffset: offset, scheme: scheme === SymmetricEncryptionScheme.AesCbc ? 'aes-cbc' : 'aead', operation, seed,
      ownerKeyHex: hex(keyToUint8Array(ownerKey.object)), currentOwnerKeyHex: hex(keyToUint8Array(current.object)),
      sessionKeyHex: hex(keyToUint8Array(sessionKey)), ownerKeyVersion: ownerKey.version,
      wrappedSessionKeyHex: hex(ownerEncSessionKey), keyCalls, randomCalls: random.calls, input: json(input), mappedInput, wire: JSON.parse(wire.getJsonRepresentation()), wireJson: wire.getJsonRepresentation() })
   }
  }
}
const htmlVectors = ['', 'one', 'a < b & c\nnext', 'a\r\nb\n', '\n\n', '> quoted\n> next\nplain',
  '>> nested\n> outer\nplain', '>\n>>', '>no space', '>  two spaces', '  > literal', 'λ 😀 & <tag>'].map(plain => ({ plain, html: plainTextToHtml(plain) }))
export const output = { htmlVectors, draftUpdateReturnWire, upstreamCommit: '332e57461cdc4fe4a4ad9bdc8555f56df02931c0', vectors }

export { TestRandomizer, resolver }
