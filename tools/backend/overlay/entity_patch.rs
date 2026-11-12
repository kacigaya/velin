//! One changed encrypted value as a PATCH (EntityRestClient.ts:690-720, PatchGenerator.ts:85-110), and the
//! matching read of values that use the instance key (ValueDecryptor.ts AeadWithInstanceKeyFromGroupKeyDecryptor).
//! The release SDK has neither: its update_instance sends a PUT, which the server refuses for these types.
use crate::element_value::{ElementValue, ParsedEntity};
use crate::entities::generated::sys::{Patch, PatchList};
use crate::metamodel::{ModelValue, TypeModel};
use crate::ApiCallError;
use base64::prelude::{BASE64_STANDARD, BASE64_URL_SAFE_NO_PAD};
use base64::Engine;
use crypto_primitives::aead_facade::{AeadFacade, AeadSubKeys};
use crypto_primitives::aes::InitializationVector;
use crypto_primitives::key::GenericAesKey;
use crypto_primitives::randomizer_facade::RandomizerFacade;
use crypto_primitives::versioned::VersionedAesKey;

/// SymmetricCipherUtils.ts validateKdfNonceLength.
pub(crate) const KDF_NONCE_LENGTH: usize = 32;
/// SymmetricCipherVersion.AeadWithInstanceKey, then a one-byte group key version.
const INSTANCE_KEY_CIPHER_VERSION: u8 = 2;
/// PatchGenerator.ts PatchOperationType.REPLACE.
const PATCH_REPLACE: i64 = 2;

/// getSubKeyInfoOnUpdate: CBC uses the instance's session key, AEAD the owner's current group key and the KDF nonce.
pub(crate) enum VelinValueKey {
    Session(GenericAesKey),
    Instance { group_key: VersionedAesKey, kdf_nonce: Vec<u8> },
}

fn invalid() -> ApiCallError { ApiCallError::internal("Invalid entity patch".into()) }

fn value_model<'a>(model: &'a TypeModel, attribute: &str) -> Result<&'a ModelValue, ApiCallError> {
    model.values.iter().find(|(id, _)| String::from(**id) == attribute).map(|(_, value)| value).ok_or_else(invalid)
}

// CryptoTypes.ts AEAD_ATTRIBUTE_ON_UNAUTHENTICATED_INSTANCE_INSTANCE_KEY_DOMAIN. A top-level value path is its ID;
// CanonicalId.ts only rewrites nested MailDetailsDraft paths.
fn associated_data(attribute: &str) -> Vec<u8> { format!("attributeEncIK\u{001f}{attribute}").into_bytes() }

fn instance_sub_keys(model: &TypeModel, group_key: &VersionedAesKey, kdf_nonce: &[u8]) -> Result<AeadSubKeys, ApiCallError> {
    if kdf_nonce.len() != KDF_NONCE_LENGTH { return Err(invalid()) }
    Ok(AeadSubKeys::derive_sub_keys_aead_with_instance_key_from_group_key(group_key, kdf_nonce,
        &crate::velin_session_transfer::canonical_context(model, false)))
}

/// CryptoMapper.ts encryptValue for one top-level value that may change (final values are never patched).
pub(crate) fn encrypt_value(model: &TypeModel, attribute: &str, value: &ElementValue, key: &VelinValueKey,
    random: &RandomizerFacade) -> Result<Vec<u8>, ApiCallError> {
    let value_model = value_model(model, attribute)?;
    if !value_model.encrypted || value_model.is_final || matches!(value, ElementValue::Null) { return Err(invalid()) }
    let plain = crate::velin_session_transfer::binary(&value_model.value_type, value)?;
    match key {
        VelinValueKey::Session(key) => key.encrypt_data(&plain, InitializationVector::generate(random)).map_err(|_| invalid()),
        VelinValueKey::Instance { group_key, kdf_nonce } => AeadFacade::new(random.clone())
            .encrypt(&instance_sub_keys(model, group_key, kdf_nonce)?, plain, &associated_data(attribute))
            .map_err(|_| invalid()),
    }
}

/// The group key version of a value encrypted with the instance key, or None for any other cipher.
pub(crate) fn instance_key_version(ciphertext: &[u8]) -> Option<u64> {
    (ciphertext.len() > 3 && ciphertext[0] == INSTANCE_KEY_CIPHER_VERSION && ciphertext[1] == 0).then(|| u64::from(ciphertext[2]))
}

/// Top-level values that use the instance key, with their group key versions, plus the owner group and KDF nonce
/// that derive it. Empty when the type has no such values or the instance cannot derive the key.
pub(crate) fn instance_key_values(model: &TypeModel, entity: &ParsedEntity)
    -> (Vec<(String, u64)>, Option<(crate::GeneratedId, Vec<u8>)>) {
    let found: Vec<(String, u64)> = model.values.iter()
        .filter(|(_, value)| value.encrypted)
        .filter_map(|(id, _)| {
            let attribute = String::from(*id);
            match entity.get(&attribute) {
                Some(ElementValue::Bytes(bytes)) => instance_key_version(bytes).map(|version| (attribute, version)),
                _ => None,
            }
        })
        .collect();
    if found.is_empty() { return (found, None) }
    let attribute = |name| model.get_attribute_id_by_attribute_name(name).ok().and_then(|id| entity.get(&id));
    let context = match (attribute("_ownerGroup"), attribute("_kdfNonce")) {
        (Some(ElementValue::IdGeneratedId(group)), Some(ElementValue::Bytes(nonce))) => Some((group.clone(), nonce.clone())),
        _ => None,
    };
    (found, context)
}

/// Decrypts and parses one top-level value. A wrong key or an altered value fails closed.
pub(crate) fn decrypt_instance_value(model: &TypeModel, attribute: &str, ciphertext: &[u8], group_key: &VersionedAesKey,
    kdf_nonce: &[u8]) -> Result<ElementValue, ApiCallError> {
    let value_model = value_model(model, attribute)?;
    if !value_model.encrypted { return Err(invalid()) }
    let plain = AeadFacade::new(RandomizerFacade::from_core(rand_core::OsRng))
        .decrypt(&instance_sub_keys(model, group_key, kdf_nonce)?, ciphertext, &associated_data(attribute))
        .map_err(|_| invalid())?;
    crate::entities::entity_facade::EntityFacadeImpl::parse_decrypted_value(value_model.value_type.clone(), plain)
}

/// The release decrypts every value with the session key and fails the whole instance on a MAC error. An empty
/// string is the encrypted form of a default value, so the session-key pass skips these values.
pub(crate) fn blank_instance_values(entity: &mut ParsedEntity, values: &[(String, ElementValue)]) {
    for (attribute, _) in values {
        entity.insert(attribute.clone(), ElementValue::String(String::new()));
    }
}

/// Puts the plaintext of the blanked values back after the session-key pass.
pub(crate) fn apply_instance_values(entity: &mut ParsedEntity, values: Vec<(String, ElementValue)>) {
    entity.extend(values);
}

fn aggregate_id(random: &RandomizerFacade) -> crate::CustomId {
    crate::CustomId(BASE64_URL_SAFE_NO_PAD.encode(random.generate_random_array::<4>()))
}

/// PatchGenerator.ts: one REPLACE of a top-level value, its ciphertext in standard base64.
pub(crate) fn patch_list(attribute: &str, ciphertext: &[u8], random: &RandomizerFacade) -> PatchList {
    PatchList { _id: Some(aggregate_id(random)), patches: vec![Patch { _id: Some(aggregate_id(random)),
        patchOperation: PATCH_REPLACE, attributePath: attribute.to_owned(), value: Some(BASE64_STANDARD.encode(ciphertext)) }] }
}
