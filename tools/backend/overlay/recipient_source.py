"""Expose verified recipient resolution from the pinned release SDK."""
from pathlib import Path
import sys

root = Path(sys.argv[1]) / 'tuta-sdk/rust/sdk/src'
overlay = Path(__file__).parent
with (root / 'mail_facade.rs').open('a') as output:
    output.write((overlay / 'recipients.rs').read_text())
path = root / 'lib.rs'
text = path.read_text()
assert text.count('pub enum ApiCallError {') == 1
path.write_text(text.replace('pub enum ApiCallError {', '''pub enum ApiCallError {
    #[error("Invalid recipient key data")]
    VelinRecipientInvalidKey,
    #[error("Recipient lookup rate limited")]
    VelinRecipientRateLimited,'''))

path = root / 'mail_facade.rs'
text = path.read_text()
old = '\tservice_executor: Arc<ResolvingServiceExecutor>,\n}'
assert text.count(old) == 1
text = text.replace(old, '\tservice_executor: Arc<ResolvingServiceExecutor>,\n    velin_trust: std::sync::RwLock<Option<Arc<dyn VelinIdentityTrustStore>>>,\n    velin_recipient_slots: tokio::sync::Semaphore,\n}')
old = '\t\tMailFacade {\n\t\t\tcrypto_entity_client,'
assert text.count(old) == 1
text = text.replace(old, '\t\tMailFacade {\n            velin_trust: std::sync::RwLock::new(None),\n            velin_recipient_slots: tokio::sync::Semaphore::new(5),\n\t\t\tcrypto_entity_client,')
path.write_text(text)

path = root / 'crypto/public_key_provider.rs'
text = path.read_text()
assert text.count('\tfn convert_to_versioned_public_keys(') == 1
path.write_text(text.replace('\tfn convert_to_versioned_public_keys(', '\tpub(crate) fn convert_to_versioned_public_keys('))
path = root / 'crypto/asymmetric_crypto_facade.rs'
text = path.read_text()
start = text.index('pub struct PubEncSymKey {')
end = text.index('}', start)
block = text[start:end]
for field in ['pub_enc_sym_key_bytes', 'crypto_protocol_version', 'sender_key_version', 'recipient_key_version']:
    old = f'\t{field}:'
    assert block.count(old) == 1
    block = block.replace(old, f'\tpub(crate) {field}:')
text = text[:start] + block + text[end:]
path.write_text(text)
path = root / 'crypto_entity_client.rs'
text = path.read_text()
methods = (overlay / 'send_client.rs').read_text().removeprefix('impl CryptoEntityClient {').rstrip().removesuffix('}')
assert text.count('\tpub fn serialize_entity<') == 1
path.write_text(text.replace('\tpub fn serialize_entity<', methods + '\n\tpub fn serialize_entity<'))
with (root / 'mail_facade.rs').open('a') as output:
    output.write((overlay / 'send.rs').read_text())
path = root / 'lib.rs'
text = path.read_text().replace('pub enum ApiCallError {', '''pub enum ApiCallError {
    #[error("Unsupported send operation")]
    VelinSendUnsupported { reason: u32 },
    #[error("Recipient prevents send")]
    VelinSendBlocked { reason: u32, indices: Vec<u32> },''')
path.write_text(text)

path = root / 'velin_session_transfer.rs'
text = path.read_text().replace('fn assign_ids(', 'pub(crate) fn assign_ids(')
text = text.replace('mod velin_session_transfer_tests {', 'pub(crate) mod velin_session_transfer_tests {')
text = text.replace('    struct TestRng {', '    pub(crate) struct TestRng {')
text = text.replace('        fn new(seed:', '        pub(crate) fn new(seed:')
text = text.replace('    fn provider(plain:', '    pub(crate) fn provider(plain:')
path.write_text(text)
path = root / 'services.rs'
text = path.read_text().replace('pub struct ExtraServiceParams {', 'pub struct ExtraServiceParams {\n    pub(crate) velin_send: bool,')
path.write_text(text)
path = root / 'services/service_executor.rs'
text = path.read_text()
old = '\t\t\t} else {\n\t\t\t\tparsed_entity\n\t\t\t};'
assert text.count(old) == 1
text = text.replace(old, '''            } else if extra_service_params.velin_send {
                crate::velin_session_transfer::assign_ids(&self.type_model_provider, type_model, &parsed_entity,
                    &crypto_primitives::randomizer_facade::RandomizerFacade::from_core(rand_core::OsRng))?
            } else {
                parsed_entity
            };''')
text = text.replace('if extra_service_params.velin_session_transfer.is_some() {',
                    'if extra_service_params.velin_session_transfer.is_some() || extra_service_params.velin_send {')
path.write_text(text)
(root / 'velin_sends.json').write_bytes((overlay.parent / 'vectors/sends.json').read_bytes())
with (root / 'mail_facade.rs').open('a') as output:
    output.write((overlay / 'send_tests.rs').read_text())
