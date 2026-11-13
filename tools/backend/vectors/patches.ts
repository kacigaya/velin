import { createHash } from 'node:crypto'
import assert from 'node:assert/strict'
import {
  Aes, AesCbcFacade, AeadFacade, KeyEncryption, Randomizer,
  SymmetricCipherFacade, SymmetricCipherUtils, SymmetricKeyDeriver,
  SymmetricEncryptionScheme, uint8ArrayToKey, keyToUint8Array,
} from '@tutao/crypto'
import { IncomingServerJson } from '@release/src/platform-kit/instance-pipeline/TypeMapper'
import { InstancePipeline } from '@release/src/platform-kit/instance-pipeline/InstancePipeline'
import { ClientModelInfo, ClientOnlyTypeModelResolver } from '@release/src/platform-kit/instance-pipeline/EntityFunctions'
import { EntityRestClient } from '@release/src/platform-kit/network/EntityRestClient'
import * as types from '@tutao/entities/tutanota'
import tutanota from '@release/src/entities/tutanota/TypeModels.js'
import sys from '@release/src/entities/sys/TypeModels.js'
import storage from '@release/src/entities/storage/TypeModels.js'
import { Mode, PaymentSetup } from '@tutao/app-env'
Object.assign(globalThis, { env: { mode: Mode.Test, paymentSetup: PaymentSetup.None, platformId: null, dist: false,
  staticUrl: null, versionNumber: '366.261105.0', timeout: 1000, domainConfigs: {}, networkDebugging: false,
  clientName: null, integrationPlatform: null, shadowDomAppRoot: null } })

// Same byte stream as drafts.ts: SHA-256(seed || big-endian u32 counter).
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
  offset() { return this.calls.reduce((sum, size) => sum + size, 0) }
}
const modelInfo = new ClientModelInfo()
for (const [app, clientModel, version] of [['tutanota', tutanota, 117], ['sys', sys, 157], ['storage', storage, 15]] as const) {
  modelInfo.registerApp({ app, clientModel, modelInfo: { version } })
}
const resolver = new ClientOnlyTypeModelResolver(modelInfo)
const hex = (b: Uint8Array) => Buffer.from(b).toString('hex')

// SendMailModel.ts:1314-1328 marks the answered mail (encrypted replyType, 466). MailViewerViewModel.ts:609-620
// marks a reported mail as phishing (plain phishingStatus, 1021). EntityRestClient.ts:690-720 sends the PATCH.
const cases = [
  { scheme: SymmetricEncryptionScheme.AesCbc, sessionKeyBytes: 32, field: 'replyType', from: '0', to: '1' },
  { scheme: SymmetricEncryptionScheme.AesCbc, sessionKeyBytes: 16, field: 'replyType', from: '2', to: '3' },
  { scheme: SymmetricEncryptionScheme.Aead, sessionKeyBytes: 32, field: 'replyType', from: '0', to: '2' },
  { scheme: SymmetricEncryptionScheme.Aead, sessionKeyBytes: 16, field: 'replyType', from: '1', to: '3' },
  { scheme: SymmetricEncryptionScheme.Aead, sessionKeyBytes: 32, field: 'phishingStatus', from: '0', to: '1' },
] as const
const attributes = { replyType: '466', phishingStatus: '1021' }
const vectors = []
for (const c of cases) {
  const name = c.scheme === SymmetricEncryptionScheme.AesCbc ? 'aes-cbc' : 'aead'
  const seed = c.field === 'replyType' ? `velin-patch-${name}-${c.sessionKeyBytes}-v1` : `velin-patch-${c.field}-${name}-v1`
  const random = new TestRandomizer(seed)
  const utils = new SymmetricCipherUtils(random)
  const cipher = new SymmetricCipherFacade(new AesCbcFacade(), new AeadFacade(utils), new SymmetricKeyDeriver(), utils)
  const keyEncryption = new KeyEncryption(cipher, new Aes(cipher))
  const sessionKey = uint8ArrayToKey(Uint8Array.from({ length: c.sessionKeyBytes }, (_, i) => i + 32))
  const current = { object: uint8ArrayToKey(Uint8Array.from({ length: 32 }, (_, i) => i)), version: 5 }
  const historical = { object: uint8ArrayToKey(Uint8Array.from({ length: 32 }, (_, i) => i + 64)), version: 3 }
  const kdfNonce = c.scheme === SymmetricEncryptionScheme.Aead ? Uint8Array.from({ length: 32 }, (_, i) => i + 128) : null
  const keyCalls: string[] = []
  const loader = {
    keyEncryption,
    getCurrentSymGroupKey: async (group: string) => { keyCalls.push(`current:${group}`); return current },
    loadSymGroupKey: async (group: string, version: number) => {
      keyCalls.push(`historical:${group}:${version}`)
      if (version === current.version) return current.object
      assert.equal(version, historical.version)
      return historical.object
    },
  }
  const auth = { getDefaultSymmetricEncryptionScheme: () => c.scheme,
    createAuthHeaders: () => ({ accessToken: 'test-token' }),
    isFullyLoggedIn: () => true,
    getLoggedInUser: () => { throw new Error('Unexpected user lookup') },
    getCurrentUserGroupKey: () => { throw new Error('Unexpected user-group key lookup') },
    setLeaderStatus: () => { throw new Error('Unexpected event bus state') },
    getUserGroupId: () => { throw new Error('Unexpected user-group ID lookup') } }
  const pipeline = new InstancePipeline(resolver, () => loader, cipher, auth, () => ({ makeInstanceKeyProvider: async () => null }), random, keyEncryption)
  const address = types.createMailAddress({ name: 'Sender λ', address: 'sender@example.invalid', contact: null })
  const mail = types.createMail({
    _id: ['test-mail-list', 'test-original'], _permissions: 'test-permissions', _format: '0',
    _ownerGroup: 'test-mail-group', _ownerEncSessionKey: keyEncryption.encryptKey(historical.object, sessionKey),
    _ownerKeyVersion: String(historical.version), _kdfNonce: kdfNonce,
    subject: 'Original λ', receivedDate: new Date(1794492328000), state: '2', unread: false, confidential: true,
    replyType: c.field === 'replyType' ? c.from : '0', differentEnvelopeSender: null, listUnsubscribe: false, movedTime: null,
    phishingStatus: c.field === 'phishingStatus' ? c.from : '0',
    authStatus: null, method: '0', recipientCount: '1', encryptionAuthStatus: null, processingState: '0',
    processNeeded: false, sendAt: null, serverClassificationData: null,
    sender: address, attachments: [], conversationEntry: ['test-entry-list', 'test-entry'], firstRecipient: null,
    mailDetails: ['test-details-archive', 'test-details'], mailDetailsDraft: null, bucketKey: null, sets: [],
    clientSpamClassifierResult: null,
  })
  // The stored mail uses its session key for every value, as delivered mail does.
  const stored = (await pipeline.mapAndEncrypt(types.MailTypeRef, mail, sessionKey)).getJsonRepresentation()
  const serverModel = await resolver.resolveServerTypeReference(types.MailTypeRef)
  const loaded = await pipeline.decryptAndMap<types.Mail>(IncomingServerJson.expectSingleInstance(stored, serverModel), sessionKey)
  loaded[c.field] = c.to

  // Record each attribute encryption with the random offset before it, to find the patched value.
  const encryptions: { offset: number, ciphertext: string }[] = []
  for (const method of ['encryptBytes', 'encryptBytesWithAead'] as const) {
    const original = cipher[method].bind(cipher)
    ;(cipher as any)[method] = (...args: unknown[]) => {
      const offset = random.offset()
      const result = (original as any)(...args)
      encryptions.push({ offset, ciphertext: Buffer.from(result).toString('base64') })
      return result
    }
  }
  const requests: { path: string, method: string, headers: Record<string, string>, body: string }[] = []
  const rest = { request: async (path: string, method: string, options: any) => {
    requests.push({ path, method, headers: options.headers, body: options.body.payload })
    return null
  } }
  const crypto = {
    getCurrentSymGroupKey: async (group: string) => { keyCalls.push(`current:${group}`); return current },
    postUpdateKdfNonceService: async () => { throw new Error('Unexpected kdf nonce request') },
  }
  const client = new EntityRestClient(auth as any, rest as any, () => crypto as any, pipeline, null as any, resolver,
    () => ({ resolveSessionKeyWithOwnerKeyProvider: async () => sessionKey }) as any, null as any, utils)
  await client.update(loaded)
  assert.equal(requests.length, 1)
  const [request] = requests
  const body = JSON.parse(request.body)
  const patches = body['2616']
  assert.equal(patches.length, 1)
  const [patch] = patches
  const attribute = attributes[c.field]
  assert.equal(patch['2570'], attribute)
  assert.equal(patch['2569'], '2')
  // An encrypted value comes from one recorded encryption; a plain one is sent as it is.
  const value = encryptions.find(e => e.ciphertext === patch['2571'])
  if (c.field === 'replyType') assert.ok(value, 'patched value must come from one recorded encryption')
  else assert.equal(patch['2571'], c.to)

  // The server applies the patch. The release pipeline must read the result.
  const patched = JSON.parse(stored)
  patched[attribute] = patch['2571']
  const reread = await pipeline.decryptAndMap<types.Mail>(IncomingServerJson.expectSingleInstance(JSON.stringify(patched), serverModel), sessionKey)
  assert.equal(reread[c.field], c.to)
  assert.equal(reread.subject, mail.subject)

  vectors.push({ scheme: name, seed, field: c.field, attribute, valueRandomOffset: value?.offset ?? null, from: c.from, to: c.to,
    sessionKeyHex: hex(keyToUint8Array(sessionKey)), currentGroupKeyHex: hex(keyToUint8Array(current.object)),
    currentGroupKeyVersion: current.version, historicalGroupKeyHex: hex(keyToUint8Array(historical.object)),
    historicalGroupKeyVersion: historical.version, kdfNonceHex: kdfNonce == null ? null : hex(kdfNonce), keyCalls,
    path: request.path, method: request.method, headers: { ...request.headers, accessToken: undefined },
    body: request.body, storedMail: stored, patchedMail: JSON.stringify(patched) })
}
export const output = { upstreamCommit: '332e57461cdc4fe4a4ad9bdc8555f56df02931c0', vectors }
