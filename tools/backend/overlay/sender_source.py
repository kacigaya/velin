"""Check the sender key of received TutaCrypt mail against Velin's pinned identities."""
from pathlib import Path
import sys

root = Path(sys.argv[1]) / 'tuta-sdk/rust/sdk/src'
overlay = Path(__file__).parent

path = root / 'crypto/asymmetric_crypto_facade.rs'
text = path.read_text()
old = '\tpublic_key_provider: Arc<PublicKeyProvider>,\n}'
assert text.count(old) == 1
text = text.replace(old, '\tpublic_key_provider: Arc<PublicKeyProvider>,\n\tvelin_trust: std::sync::RwLock<Option<Arc<dyn crate::mail_facade::VelinIdentityTrustStore>>>,\n}')
old = '\t\t\tpublic_key_provider,\n\t\t}\n\t}'
assert text.count(old) == 1
text = text.replace(old, '\t\t\tpublic_key_provider,\n\t\t\tvelin_trust: std::sync::RwLock::new(None),\n\t\t}\n\t}')
old = '\t/// Verifies whether the key that the public key service returns is the same as the one used for encryption.'
assert text.count(old) == 1
text = text.replace(old, (overlay / 'sender_auth.rs').read_text() + old)
# The release compares only with the server's key; its own comment asks for the verified key instead.
old = '\t) -> Result<EncryptionAuthStatus, AsymmetricCryptoError> {\n\t\tlet pub_keys = self\n'
assert text.count(old) == 1
text = text.replace(old, '''\t) -> Result<EncryptionAuthStatus, AsymmetricCryptoError> {
\t\tlet store = self.velin_trust.read().ok().and_then(|slot| slot.clone());
\t\tif let Some(store) = store {
\t\t\tif identifier.identifier_type == crate::tutanota_constants::PublicKeyIdentifierType::MailAddress
\t\t\t\t&& identifier.identifier != crate::tutanota_constants::SYSTEM_GROUP_MAIL_ADDRESS {
\t\t\t\treturn self.velin_authenticate_sender(store, identifier.identifier, sender_identity_pub_key).await;
\t\t\t}
\t\t}
\t\tlet pub_keys = self
''')
text += (overlay / 'sender_auth_tests.rs').read_text()
path.write_text(text)

# Only temporary errors may fail the read. The release keeps only connection errors temporary, so a 429 or a
# full trust store would become a cached "key does not match" warning.
path = root / 'crypto_entity_client.rs'
text = path.read_text()
old = """					if let ApiCallError::ServerResponseError {
						source: HttpError::ConnectionError,
					} = api_call_error
					{
						return Err(api_call_error);
					} else if let ApiCallError::RestClient {
						source: RestClientError::NetworkError,
					} = api_call_error
					{
						return Err(api_call_error);
					}"""
assert text.count(old) == 1
text = text.replace(old, "\t\t\t\t\tif velin_temporary_auth_error(&api_call_error) {\n\t\t\t\t\t\treturn Err(api_call_error);\n\t\t\t\t\t}")
text += (overlay / 'sender_temporary.rs').read_text()
path.write_text(text)

path = root / 'lib.rs'
text = path.read_text()
old = '\tpub customer_facade: Arc<CustomerFacade>,\n}\n\nimpl LoggedInSdk {'
assert text.count(old) == 1
text = text.replace(old, '\tpub customer_facade: Arc<CustomerFacade>,\n\tvelin_asymmetric_crypto_facade: Arc<AsymmetricCryptoFacade>,\n}\n\nimpl LoggedInSdk {')
old = '\t\t\tcontact_facade,\n\t\t\tcustomer_facade,\n\t\t}))'
assert text.count(old) == 1
text = text.replace(old, '\t\t\tcontact_facade,\n\t\t\tcustomer_facade,\n\t\t\tvelin_asymmetric_crypto_facade: asymmetric_crypto_facade,\n\t\t}))')
old = '#[uniffi::export]\nimpl LoggedInSdk {\n'
assert text.count(old) == 1
text = text.replace(old, old + '''\t/// Received TutaCrypt mail checks its sender against this store. Set it before reading mail.
\tpub fn velin_set_sender_trust_store(&self, store: Arc<dyn crate::mail_facade::VelinIdentityTrustStore>) {
\t\tself.velin_asymmetric_crypto_facade.velin_set_sender_trust_store(store);
\t}

''')
path.write_text(text)
