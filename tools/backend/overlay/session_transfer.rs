//! WithSessionKey transfer encryption from the pinned InstancePipeline.
use crate::element_value::{ElementValue, ParsedEntity};
use crate::entities::entity_facade::EntityFacadeImpl;
use crate::metamodel::{AssociationType, Cardinality, ElementType, TypeModel, ValueType};
use crate::type_model_provider::TypeModelProvider;
use crate::user_facade::VelinEncryptionScheme;
use crate::{ApiCallError, TypeRef};
use crypto_primitives::aead_facade::{AeadFacade, AeadSubKeys};
use crypto_primitives::aes::{Aes256Key, InitializationVector};
use crypto_primitives::key::GenericAesKey;
use crypto_primitives::randomizer_facade::RandomizerFacade;
use crypto_primitives::versioned::VersionedAesKey;
use base64::prelude::BASE64_URL_SAFE_NO_PAD;
use base64::Engine;

pub(crate) struct VelinSessionTransferEncryption {
    pub owner_key: VersionedAesKey,
    pub scheme: VelinEncryptionScheme,
}

#[derive(Clone, Default)]
struct Path { value: String, cut: bool }
impl Path {
    fn attribute(&self, id: String, transferred: Option<u64>) -> Self {
        let cut = transferred.is_some();
        let prefix = if cut && !self.cut { "" } else { &self.value };
        Self { value: format!("{prefix}{}", transferred.map_or(id, |id| id.to_string())), cut }
    }
    fn aggregate(&self, id: &str) -> Self {
        Self { value: format!("{}/{id}/", self.value), cut: self.cut }
    }
    fn associated_data(&self, model: &TypeModel) -> Vec<u8> {
        let path = if model.app.to_string() == "tutanota" && self.value.starts_with("1297/") {
            self.value.replacen("1297/", "1305/", 1)
        } else { self.value.clone() };
        format!("attributeEncSK\u{001f}{path}").into_bytes()
    }
}

fn invalid() -> ApiCallError { ApiCallError::internal("Invalid session transfer encryption".into()) }
fn canonical_context(model: &TypeModel, target: bool) -> String {
    let id = if target { model.target_type_id.map_or(String::from(model.id), |id| id.to_string()) }
        else { String::from(model.id) };
    let id = if model.app.to_string() == "tutanota" && id == "1290" { "1298" } else { &id };
    format!("{}/{id}", model.app)
}

pub(crate) fn encrypt(
    models: &TypeModelProvider, model: &TypeModel, input: &ParsedEntity,
    params: &VelinSessionTransferEncryption, random: &RandomizerFacade,
) -> Result<ParsedEntity, ApiCallError> {
    // Assign every aggregate ID before encryption, as ModelMapper does.
    let input = assign_ids(models, model, input, random)?;
    transform(models, model, &input, Some(params), None, None, &Path::default(), random, false)
}

pub(crate) fn decrypt(
    models: &TypeModelProvider, model: &TypeModel, input: &ParsedEntity,
    session_key: &GenericAesKey, random: &RandomizerFacade,
) -> Result<ParsedEntity, ApiCallError> {
    let context = canonical_context(model, false);
    transform(models, model, input, None, Some(session_key), Some(&context), &Path::default(), random, true)
}

fn assign_ids(models: &TypeModelProvider, model: &TypeModel, input: &ParsedEntity, random: &RandomizerFacade) -> Result<ParsedEntity, ApiCallError> {
    let mut out = input.clone();
    if model.element_type == ElementType::Aggregated {
        let id = model.get_attribute_id_by_attribute_name("_id")?;
        if matches!(out.get(&id), Some(ElementValue::Null)) {
            out.insert(id, ElementValue::String(BASE64_URL_SAFE_NO_PAD.encode(random.generate_random_array::<4>())));
        }
    }
    let mut associations: Vec<_> = model.associations.iter().collect();
    associations.sort_by_key(|(id, _)| **id);
    for (id, association) in associations {
        if association.association_type != AssociationType::Aggregation { continue }
        let child = models.resolve_client_type_ref(&TypeRef::new(association.dependency.unwrap_or(model.app), association.ref_type_id)).ok_or_else(invalid)?;
        let key = String::from(*id);
        let Some(ElementValue::Array(items)) = input.get(&key) else { return Err(invalid()) };
        let mut mapped = Vec::with_capacity(items.len());
        for item in items {
            let ElementValue::Dict(item) = item else { return Err(invalid()) };
            mapped.push(ElementValue::Dict(assign_ids(models, child, item, random)?));
        }
        out.insert(key, ElementValue::Array(mapped));
    }
    Ok(out)
}

// A transfer aggregate starts its own key context until the first transferred
// attribute cuts the service nesting from the authenticated path.
fn transform(
    models: &TypeModelProvider, model: &TypeModel, input: &ParsedEntity,
    params: Option<&VelinSessionTransferEncryption>, inherited_key: Option<&GenericAesKey>, inherited_context: Option<&str>,
    path: &Path, random: &RandomizerFacade, decrypting: bool,
) -> Result<ParsedEntity, ApiCallError> {
    let mut key = inherited_key.cloned();
    let mut context = inherited_context.map(str::to_owned);
    if model.target_type_id.is_some() && !path.cut {
        if let Some(params) = params {
            let wrapped_id = model.get_attribute_id_by_attribute_name("_ownerEncSessionKey").ok();
            key = match wrapped_id.and_then(|id| input.get(&id)) {
                Some(ElementValue::Bytes(wrapped)) => Some(params.owner_key.object.decrypt_aes_key(wrapped).map_err(|_| invalid())?),
                _ => None,
            };
        }
        context = Some(canonical_context(model, true));
    }
    let aead = AeadFacade::new(random.clone());
    let mut output = input.clone();
    let mut values: Vec<_> = model.values.iter().collect();
    values.sort_by_key(|(id, _)| **id);
    for (id, value_model) in values {
        let id = String::from(*id);
        let value = input.get(&id).ok_or_else(invalid)?;
        if !value_model.encrypted { continue }
        if matches!(value, ElementValue::Null) {
            if value_model.cardinality != Cardinality::ZeroOrOne { return Err(invalid()) }
            continue;
        }
        let key = key.as_ref().ok_or_else(invalid)?;
        let value_path = path.attribute(id.clone(), value_model.transferred_attribute_id);
        let use_aead = if decrypting {
            match value {
                ElementValue::Bytes(bytes) => bytes.first() == Some(&3),
                ElementValue::String(s) if s.is_empty() => {
                    output.insert(id, if value_model.cardinality == Cardinality::ZeroOrOne { ElementValue::Null } else { value_model.value_type.get_default() });
                    continue;
                },
                _ => return Err(invalid()),
            }
        } else { params.is_some_and(|p| p.scheme == VelinEncryptionScheme::Aead) };
        let sub_keys = if use_aead {
            Some(aead_keys(key, context.as_deref().ok_or_else(invalid)?)?)
        } else { None };
        let result = if decrypting {
            let ElementValue::Bytes(bytes) = value else { return Err(invalid()) };
            let plain = if let Some(sub_keys) = &sub_keys {
                aead.decrypt(sub_keys, bytes, &value_path.associated_data(model)).map_err(|_| invalid())?
            } else { key.decrypt_data(bytes).map_err(|_| invalid())? };
            EntityFacadeImpl::parse_decrypted_value(value_model.value_type.clone(), plain)?
        } else {
            let plain = binary(&value_model.value_type, value)?;
            let ciphertext = if let Some(sub_keys) = &sub_keys {
                aead.encrypt(sub_keys, plain, &value_path.associated_data(model)).map_err(|_| invalid())?
            } else { key.encrypt_data(&plain, InitializationVector::generate(random)).map_err(|_| invalid())? };
            ElementValue::Bytes(ciphertext)
        };
        output.insert(id, result);
    }
    let mut associations: Vec<_> = model.associations.iter().collect();
    associations.sort_by_key(|(id, _)| **id);
    for (id, association) in associations {
        if association.association_type != AssociationType::Aggregation { continue }
        let child_ref = TypeRef::new(association.dependency.unwrap_or(model.app), association.ref_type_id);
        let client_model;
        let child = if decrypting {
            client_model = models.resolve_server_type_ref(&child_ref).ok_or_else(invalid)?;
            client_model.as_ref()
        } else { models.resolve_client_type_ref(&child_ref).ok_or_else(invalid)? };
        let id = String::from(*id);
        let Some(ElementValue::Array(items)) = input.get(&id) else { return Err(invalid()) };
        let association_path = path.attribute(id.clone(), association.transferred_attribute_id);
        let mut mapped = Vec::with_capacity(items.len());
        for item in items {
            let ElementValue::Dict(item) = item else { return Err(invalid()) };
            let aggregate_id = child.get_attribute_id_by_attribute_name("_id")?;
            let aggregate_id = match item.get(&aggregate_id) {
                Some(ElementValue::IdCustomId(id)) => id.as_str(),
                Some(ElementValue::String(id)) => id,
                _ => return Err(invalid()),
            };
            mapped.push(ElementValue::Dict(transform(models, child, item, params, key.as_ref(), context.as_deref(),
                &association_path.aggregate(aggregate_id), random, decrypting)?));
        }
        output.insert(id, ElementValue::Array(mapped));
    }
    Ok(output)
}

fn binary(kind: &ValueType, value: &ElementValue) -> Result<Vec<u8>, ApiCallError> {
    match (kind, value) {
        (ValueType::Bytes, ElementValue::Bytes(bytes)) => Ok(bytes.clone()),
        (ValueType::String, ElementValue::String(s)) => Ok(s.as_bytes().to_vec()),
        (ValueType::CompressedString, ElementValue::String(s)) => if s.len() <= 1024 * 1024 { Ok(crate::velin_draft_compression::compress(s.as_bytes())) } else { Err(invalid()) },
        (ValueType::Number, ElementValue::Number(n)) => Ok(n.to_string().into_bytes()),
        (ValueType::Boolean, ElementValue::Bool(b)) => Ok(if *b { b"1" } else { b"0" }.to_vec()),
        (ValueType::Date, ElementValue::Date(d)) => Ok(d.as_millis().to_string().into_bytes()),
        _ => Err(invalid()),
    }
}

pub(crate) fn wire_json(raw: &crate::json_element::RawEntity) -> Result<Vec<u8>, ApiCallError> {
    fn write(value: &serde_json::Value, out: &mut Vec<u8>) -> Result<(), serde_json::Error> {
        match value {
            serde_json::Value::Object(map) => {
                out.push(b'{');
                let mut entries: Vec<_> = map.iter().collect();
                entries.sort_by(|(a, _), (b, _)| (a.len(), *a).cmp(&(b.len(), *b)));
                for (index, (key, value)) in entries.into_iter().enumerate() {
                    if index > 0 { out.push(b','); }
                    serde_json::to_writer(&mut *out, key)?;
                    out.push(b':');
                    write(value, out)?;
                }
                out.push(b'}');
            },
            serde_json::Value::Array(values) => {
                out.push(b'[');
                for (index, value) in values.iter().enumerate() {
                    if index > 0 { out.push(b','); }
                    write(value, out)?;
                }
                out.push(b']');
            },
            value => serde_json::to_writer(out, value)?,
        }
        Ok(())
    }
    let value = serde_json::to_value(raw).map_err(|_| invalid())?;
    let mut output = Vec::new();
    write(&value, &mut output).map_err(|_| invalid())?;
    Ok(output)
}

pub(crate) fn contains_session_aead(models: &TypeModelProvider, model: &TypeModel, input: &ParsedEntity) -> bool {
    if model.values.values().any(|v| v.encrypted && matches!(input.get(&String::from(v.id)), Some(ElementValue::Bytes(bytes)) if bytes.first() == Some(&3))) {
        return true;
    }
    model.associations.values().filter(|a| a.association_type == AssociationType::Aggregation).any(|a| {
        let Some(child) = models.resolve_server_type_ref(&TypeRef::new(a.dependency.unwrap_or(model.app), a.ref_type_id)) else { return false };
        matches!(input.get(&String::from(a.id)), Some(ElementValue::Array(items)) if items.iter().any(|item| matches!(item, ElementValue::Dict(item) if contains_session_aead(models, &child, item))))
    })
}

// The release permits legacy 128-bit session keys as AEAD input key material.
// The primitive's existing adapter accepts only Aes256Key. Keep its 256-bit path
// and use the same release KDF/domain for historical 128-bit session keys.
fn aead_keys(key: &GenericAesKey, context: &str) -> Result<AeadSubKeys, ApiCallError> {
    match key {
        GenericAesKey::Aes256(key) => Ok(AeadSubKeys::derive_from_session_key(key, context)),
        GenericAesKey::Aes128(_) => {
            let bytes = zeroize::Zeroizing::new(crypto_primitives::blake3::blake3_kdf(
                &[key.as_bytes()], &format!("SK instanceSessionKey\u{001f}{context}"), 64));
            Ok(AeadSubKeys::AeadWithSessionKeySubKeys {
                encryption_key: Aes256Key::from_bytes(&bytes[..32]).map_err(|_| invalid())?,
                authentication_key: Aes256Key::from_bytes(&bytes[32..]).map_err(|_| invalid())?,
            })
        },
    }
}

pub(crate) fn wrap_session_key(owner: &GenericAesKey, session: &GenericAesKey, random: &RandomizerFacade) -> Vec<u8> {
    // The upstream primitive uses its fixed IV for legacy AES-128 key wrapping.
    // It ignores this argument in that branch. Attribute IVs remain random.
    let iv = match owner {
        GenericAesKey::Aes128(_) => InitializationVector::from_arr([0x88; 16]),
        GenericAesKey::Aes256(_) => InitializationVector::generate(random),
    };
    owner.encrypt_key(session, iv)
}
