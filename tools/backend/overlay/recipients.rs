#[derive(uniffi::Record, Clone)]
pub struct VelinTrustedIdentity {
    pub key: Vec<u8>,
    pub version: i64,
    pub manual: bool,
}

#[uniffi::export(with_foreign)]
#[async_trait::async_trait]
pub trait VelinIdentityTrustStore: Send + Sync + 'static {
    async fn load(&self, address: String) -> Result<Option<VelinTrustedIdentity>, ApiCallError>;
    async fn trust_on_first_use(&self, address: String, identity: VelinTrustedIdentity) -> Result<VelinTrustedIdentity, ApiCallError>;
}

// Recipient lookup follows the production PublicEncryptionKeyProvider and KeyVerificationFacade.
// Trust entries belong to the active account and must survive session restart.
#[derive(uniffi::Record, Clone)]
pub struct VelinRecipient {
    pub internal: bool,
    pub key_state: i64,
}

fn velin_public_key_message(key: &crate::entities::generated::sys::PublicKeyGetOut) -> Result<Vec<u8>, ApiCallError> {
    let invalid = || ApiCallError::VelinRecipientInvalidKey;
    let version = u8::try_from(key.pubKeyVersion).map_err(|_| invalid())?;
    let (kind, first, second): (u8, &[u8], &[u8]) = match (&key.pubRsaKey, &key.pubEccKey, &key.pubKyberKey) {
        (Some(rsa), None, None) if version == 0 => (2, &[], rsa),
        (Some(rsa), Some(ecc), None) if version == 0 => (0, ecc, rsa),
        (None, Some(ecc), Some(kyber)) => (1, ecc, kyber),
        _ => return Err(invalid()),
    };
    if kind != 2 && first.len() != 32 { return Err(invalid()) }
    if kind == 1 {
        crate::crypto::kyber::KyberPublicKey::deserialize(second).map_err(|_| invalid())?;
    } else {
        crate::crypto::rsa::RSAPublicKey::deserialize(second).map_err(|_| invalid())?;
    }
    let mut message = Vec::new();
    for bytes in [&[kind][..], &[version][..], first, second] {
        let size = u16::try_from(bytes.len()).map_err(|_| invalid())?;
        message.extend_from_slice(&size.to_be_bytes());
        message.extend_from_slice(bytes);
    }
    Ok(message)
}

fn velin_verify_recipient_key(
    key: &crate::entities::generated::sys::PublicKeyGetOut,
    identity: Option<&crate::entities::generated::sys::IdentityKeyGetOut>,
) -> Result<i64, ApiCallError> {
    use crypto_primitives::ed25519::{Ed25519PublicKey, Ed25519Signature};
    let message = velin_public_key_message(key)?;
    match (identity, key.signature.as_ref()) {
        (None, None) => Ok(0),
        (None, Some(_)) | (Some(_), None) => Ok(2),
        (Some(identity), Some(signature)) => {
            if !(0..=255).contains(&identity.publicIdentityKeyVersion) {
                return Err(ApiCallError::VelinRecipientInvalidKey);
            }
            let bytes: [u8; 32] = identity.publicIdentityKey.as_slice().try_into().map_err(|_| ApiCallError::VelinRecipientInvalidKey)?;
            let signature: [u8; 64] = signature.signature.as_slice().try_into().map_err(|_| ApiCallError::VelinRecipientInvalidKey)?;
            Ok(if Ed25519PublicKey::from_bytes(bytes).verify(&message, &Ed25519Signature::from_bytes(signature)).is_ok() { 1 } else { 2 })
        }
    }
}

fn velin_tuta_address(address: &str) -> bool {
    ["tuta.com", "tutamail.com", "tuta.io", "tutanota.com", "tutanota.de", "keemail.me"]
        .iter().any(|domain| address.ends_with(&format!("@{domain}")))
}

#[uniffi::export]
impl MailFacade {
    pub fn velin_set_identity_trust_store(&self, store: Arc<dyn VelinIdentityTrustStore>) {
        *self.velin_trust.write().unwrap() = Some(store);
    }

    pub async fn velin_resolve_recipient(&self, address: String) -> Result<VelinRecipient, ApiCallError> {
        let address = address.trim().to_lowercase();
        let (key, state) = self.velin_load_recipient_key(&address).await?;
        Ok(VelinRecipient { internal: key.is_some() || velin_tuta_address(&address), key_state: state })
    }
}

impl MailFacade {
    async fn velin_load_recipient_key(&self, address: &str) -> Result<(Option<crate::entities::generated::sys::PublicKeyGetOut>, i64), ApiCallError> {
        use crate::entities::generated::sys::{PublicKeyGetIn, IdentityKeyGetIn};
        use crate::services::generated::sys::{PublicKeyService, IdentityKeyService};
        use crate::services::ExtraServiceParams;
        let _permit = self.velin_recipient_slots.acquire().await.map_err(|_| ApiCallError::VelinRecipientInvalidKey)?;
        if address.len() > 254 || address.is_empty() || address.chars().any(|c| c.is_whitespace() || c.is_control()) ||
            address.chars().filter(|c| *c == '@').count() != 1 || address.starts_with('@') || address.ends_with('@') {
            return Err(ApiCallError::VelinRecipientInvalidKey);
        }
        let result = async {
            // Current keys must always be fetched. Only explicit historical versions may use the upstream key cache.
            let key = match self.service_executor.get::<PublicKeyService>(PublicKeyGetIn {
                _format: 0, version: None, identifierType: 0, identifier: address.to_owned(),
            }, ExtraServiceParams::default()).await {
                Ok(key) => key,
                Err(ApiCallError::ServerResponseError { source: HttpError::NotFoundError }) => {
                    return Ok((None, 0));
                },
                Err(error) => return Err(error),
            };
            velin_public_key_message(&key)?;
            let store = self.velin_trust.read().map_err(|_| ApiCallError::VelinRecipientInvalidKey)?.clone();
            let trusted = if let Some(store) = &store { store.load(address.to_owned()).await? } else { None };
            let mut trust_state = 1;
            let identity = if let Some(trusted) = trusted {
                trust_state = if trusted.manual { 4 } else { 3 };
                Some(crate::entities::generated::sys::IdentityKeyGetOut { _format: 0,
                    publicIdentityKey: trusted.key, publicIdentityKeyVersion: trusted.version })
            } else if address == crate::tutanota_constants::SYSTEM_GROUP_MAIL_ADDRESS { None } else {
                match self.service_executor.get::<IdentityKeyService>(IdentityKeyGetIn {
                    _format: 0, version: None, identifierType: 0, identifier: address.to_owned(),
                }, ExtraServiceParams::default()).await {
                    Ok(identity) => {
                        if identity.publicIdentityKey.len() != 32 || !(0..=255).contains(&identity.publicIdentityKeyVersion) {
                            return Err(ApiCallError::VelinRecipientInvalidKey);
                        }
                        if let Some(store) = &store {
                            let trusted = store.trust_on_first_use(address.to_owned(), VelinTrustedIdentity {
                                key: identity.publicIdentityKey, version: identity.publicIdentityKeyVersion, manual: false,
                            }).await?;
                            trust_state = if trusted.manual { 4 } else { 3 };
                            Some(crate::entities::generated::sys::IdentityKeyGetOut { _format: 0,
                                publicIdentityKey: trusted.key, publicIdentityKeyVersion: trusted.version })
                        } else { Some(identity) }
                    },
                    Err(ApiCallError::ServerResponseError { source: HttpError::NotFoundError }) => None,
                    Err(error) => return Err(error),
                }
            };
            let state = velin_verify_recipient_key(&key, identity.as_ref())?;
            Ok((Some(key), if state == 1 { trust_state } else { state }))
        }.await;
        match result {
            Err(ApiCallError::ServerResponseError { source: HttpError::TooManyRequestsError { .. } }) => Err(ApiCallError::VelinRecipientRateLimited),
            result => result,
        }
    }
}

#[cfg(test)]
mod velin_recipient_tests {
    use super::*;
    use crate::entities::generated::sys::{PublicKeyGetOut, IdentityKeyGetOut, PublicKeySignature};
    use crypto_primitives::compatibility_test_utils::get_compatibility_test_data;

    fn key() -> PublicKeyGetOut {
        PublicKeyGetOut { _format: 0, pubKeyVersion: 0, pubEccKey: None, pubKyberKey: None,
            pubRsaKey: Some(get_compatibility_test_data().rsa_encryption_tests.remove(0).public_key), signature: None }
    }
    #[test]
    fn velin_legacy_and_missing_signatures() {
        let mut key = key();
        assert_eq!(velin_verify_recipient_key(&key, None).unwrap(), 0);
        let identity = IdentityKeyGetOut { _format: 0, publicIdentityKey: vec![0; 32], publicIdentityKeyVersion: 0 };
        assert_eq!(velin_verify_recipient_key(&key, Some(&identity)).unwrap(), 2);
        key.signature = Some(PublicKeySignature { _id: None, signature: vec![0; 64], signingKeyVersion: 0, signatureType: 2, publicKeyVersion: 0 });
        assert_eq!(velin_verify_recipient_key(&key, None).unwrap(), 2);
        assert_eq!(velin_verify_recipient_key(&key, Some(&identity)).unwrap(), 2);
    }
    #[test]
    fn velin_signed_key_and_tampering() {
        let mut key = key();
        // RFC 8032 derivation uses the existing primitive, never a local signature implementation.
        let mut random = crypto_primitives::randomizer_facade::RandomizerFacade::from_core(rand_core::OsRng);
        let pair = crypto_primitives::ed25519::Ed25519KeyPair::generate(&mut random);
        let identity = IdentityKeyGetOut { _format: 0, publicIdentityKey: pair.public_key.to_bytes().to_vec(), publicIdentityKeyVersion: 0 };
        key.signature = Some(PublicKeySignature { _id: None, signature: pair.private_key.sign(&velin_public_key_message(&key).unwrap()).to_bytes().to_vec(),
            signingKeyVersion: 0, signatureType: 2, publicKeyVersion: 0 });
        assert_eq!(velin_verify_recipient_key(&key, Some(&identity)).unwrap(), 1);
        key.signature.as_mut().unwrap().signature[0] ^= 1;
        assert_eq!(velin_verify_recipient_key(&key, Some(&identity)).unwrap(), 2);
    }
    #[test]
    fn velin_invalid_version_and_domain_boundary() {
        let mut key = key();
        key.pubKeyVersion = 1;
        assert!(velin_public_key_message(&key).is_err());
        key.pubKeyVersion = -1;
        assert!(velin_public_key_message(&key).is_err());
        assert!(velin_tuta_address("test@tuta.com"));
        assert!(!velin_tuta_address("test@tuta.com.invalid"));
    }
}
