
#[cfg(test)]
mod velin_session_transfer_tests {
    use super::*;
    use crate::bindings::{rest_client::MockRestClient, file_client::MockFileClient};
    use crate::json_serializer::JsonSerializer;
    use crate::type_model_provider::CLIENT_TYPE_MODEL;
    use crate::metamodel::AppName;
    use crypto_primitives::versioned::Versioned;
    use rand_core::{CryptoRng, RngCore};
    use std::sync::{Arc, RwLock};
    use std::borrow::Cow;

    struct TestRng { seed: String, counter: u32, bytes: Vec<u8>, offset: usize }
    impl TestRng {
        fn new(seed: &str, offset: usize) -> Self {
            let mut rng = Self { seed: seed.into(), counter: 0, bytes: Vec::new(), offset: 0 };
            rng.fill_bytes(&mut vec![0; offset]);
            rng
        }
    }
    impl CryptoRng for TestRng {}
    impl RngCore for TestRng {
        fn next_u32(&mut self) -> u32 { rand_core::impls::next_u32_via_fill(self) }
        fn next_u64(&mut self) -> u64 { rand_core::impls::next_u64_via_fill(self) }
        fn fill_bytes(&mut self, dest: &mut [u8]) {
            for byte in dest {
                if self.offset == self.bytes.len() {
                    let mut input = self.seed.as_bytes().to_vec();
                    input.extend(self.counter.to_be_bytes());
                    self.counter += 1;
                    self.bytes = crate::crypto::sha256(&input).to_vec();
                    self.offset = 0;
                }
                *byte = self.bytes[self.offset];
                self.offset += 1;
            }
        }
        fn try_fill_bytes(&mut self, dest: &mut [u8]) -> Result<(), rand_core::Error> { self.fill_bytes(dest); Ok(()) }
    }
    fn provider(plain: bool) -> Arc<TypeModelProvider> {
        let mut p = TypeModelProvider::new_test(Arc::new(MockRestClient::default()), Arc::new(MockFileClient::default()), "https://example.invalid".into());
        if plain {
            let mut models = CLIENT_TYPE_MODEL.clone();
            for app in models.apps.values_mut() {
                for model in app.types.values_mut() {
                    let model = Arc::make_mut(model);
                    model.encrypted = false;
                    for value in model.values.values_mut() {
                        value.encrypted = false;
                        if matches!(value.value_type, ValueType::CompressedString) { value.value_type = ValueType::String; }
                    }
                }
            }
            p.client_app_models = Cow::Owned(models.clone());
            p.server_app_models = RwLock::new(Some(("test".into(), Cow::Owned(models))));
        }
        Arc::new(p)
    }
    fn fixtures() -> Vec<serde_json::Value> {
        serde_json::from_str::<serde_json::Value>(include_str!("velin_drafts.json")).unwrap()["vectors"].as_array().unwrap().clone()
    }
    fn key(value: &serde_json::Value) -> GenericAesKey {
        GenericAesKey::from_bytes(&const_hex::decode(value.as_str().unwrap()).unwrap()).unwrap()
    }
    fn random(vector: &serde_json::Value, offset: usize) -> RandomizerFacade {
        RandomizerFacade::from_core(TestRng::new(vector["seed"].as_str().unwrap(), offset))
    }
    fn inputs(vector: &serde_json::Value, plain: &Arc<TypeModelProvider>) -> (TypeRef, ParsedEntity, VelinSessionTransferEncryption) {
        let type_ref = TypeRef::new(AppName::Tutanota, if vector["operation"] == "create" { 508u64 } else { 519u64 }.into());
        let input = JsonSerializer::new(plain.clone()).parse(&type_ref, serde_json::from_value(vector["mappedInput"].clone()).unwrap()).unwrap();
        let params = VelinSessionTransferEncryption { owner_key: Versioned::new(key(&vector["ownerKeyHex"]), vector["ownerKeyVersion"].as_u64().unwrap()),
            scheme: if vector["scheme"] == "aead" { VelinEncryptionScheme::Aead } else { VelinEncryptionScheme::AesCbc } };
        (type_ref, input, params)
    }

    #[test]
    fn velin_draft_vectors_match_every_wire_byte_and_decrypt() {
        let models = provider(false);
        let plain = provider(true);
        for vector in fixtures() {
            let (tr, input, params) = inputs(&vector, &plain);
            let model = models.resolve_client_type_ref(&tr).unwrap();
            let session_key = key(&vector["sessionKeyHex"]);
            let full_random = random(&vector, 0);
            let wrapped = wrap_session_key(&params.owner_key.object, &session_key, &full_random);
            assert_eq!(const_hex::encode(wrapped), vector["wrappedSessionKeyHex"].as_str().unwrap());
            let ciphertext = encrypt(&models, model, &input, &params, &random(&vector, vector["encryptionRandomOffset"].as_u64().unwrap() as usize)).unwrap();
            let raw = JsonSerializer::new(models.clone()).serialize(&tr, ciphertext.clone()).unwrap();
            assert_eq!(String::from_utf8(wire_json(&raw).unwrap()).unwrap(), vector["wireJson"].as_str().unwrap(), "{} {}", vector["scheme"], vector["operation"]);
            fn clear_ids(models: &TypeModelProvider, model: &TypeModel, input: &mut ParsedEntity) {
                if model.element_type == ElementType::Aggregated {
                    input.insert(model.get_attribute_id_by_attribute_name("_id").unwrap(), ElementValue::Null);
                }
                for a in model.associations.values().filter(|a| a.association_type == AssociationType::Aggregation) {
                    let child = models.resolve_client_type_ref(&TypeRef::new(a.dependency.unwrap_or(model.app), a.ref_type_id)).unwrap();
                    let ElementValue::Array(items) = input.get_mut(&String::from(a.id)).unwrap() else { panic!() };
                    for item in items { let ElementValue::Dict(item) = item else { panic!() }; clear_ids(models, child, item); }
                }
            }
            let mut unmapped = input.clone();
            clear_ids(&models, model, &mut unmapped);
            let full_ciphertext = encrypt(&models, model, &unmapped, &params, &full_random).unwrap();
            let full_raw = JsonSerializer::new(models.clone()).serialize(&tr, full_ciphertext).unwrap();
            assert_eq!(wire_json(&full_raw).unwrap(), vector["wireJson"].as_str().unwrap().as_bytes());
            let decrypted = decrypt(&models, model, &ciphertext, &session_key, &random(&vector, 0)).unwrap();
            assert_eq!(decrypted, input);
        }
    }

    #[test]
    fn velin_transfer_rejects_wrong_owner_key_or_missing_wrapped_key() {
        let models = provider(false);
        for vector in fixtures() {
            let (tr, mut input, mut params) = inputs(&vector, &provider(true));
            let model = models.resolve_client_type_ref(&tr).unwrap();
            params.owner_key.object = GenericAesKey::from_bytes(&[255; 32]).unwrap();
            assert!(encrypt(&models, model, &input, &params, &random(&vector, 0)).is_err());
            let draft_data = model.associations.values().find(|a| a.name == "draftData").unwrap();
            let ElementValue::Array(items) = input.get_mut(&String::from(draft_data.id)).unwrap() else { panic!() };
            let ElementValue::Dict(data) = &mut items[0] else { panic!() };
            let data_model = models.resolve_client_type_ref(&TypeRef::new(AppName::Tutanota, draft_data.ref_type_id)).unwrap();
            let mail_assoc = data_model.associations.values().find(|a| a.name == "mail").unwrap();
            let ElementValue::Array(mails) = data.get_mut(&String::from(mail_assoc.id)).unwrap() else { panic!() };
            let ElementValue::Dict(mail) = &mut mails[0] else { panic!() };
            let mail_model = models.resolve_client_type_ref(&TypeRef::new(AppName::Tutanota, mail_assoc.ref_type_id)).unwrap();
            mail.insert(mail_model.get_attribute_id_by_attribute_name("_ownerEncSessionKey").unwrap(), ElementValue::Null);
            assert!(encrypt(&models, model, &input, &params, &random(&vector, 0)).is_err());
        }
    }

    #[test]
    fn velin_aead_rejects_tampering_and_wrong_session_keys() {
        let models = provider(false);
        for vector in fixtures().into_iter().filter(|v| v["scheme"] == "aead") {
            let (tr, input, params) = inputs(&vector, &provider(true));
            let model = models.resolve_client_type_ref(&tr).unwrap();
            let mut ciphertext = encrypt(&models, model, &input, &params, &random(&vector, 0)).unwrap();
            assert!(decrypt(&models, model, &ciphertext, &GenericAesKey::from_bytes(&[255; 32]).unwrap(), &random(&vector, 0)).is_err());
            fn tamper(entity: &mut ParsedEntity) -> bool {
                for value in entity.values_mut() {
                    match value {
                        ElementValue::Bytes(bytes) if bytes.first() == Some(&3) => { *bytes.last_mut().unwrap() ^= 1; return true; },
                        ElementValue::Array(items) => for item in items {
                            if let ElementValue::Dict(child) = item { if tamper(child) { return true } }
                        },
                        _ => {},
                    }
                }
                false
            }
            assert!(tamper(&mut ciphertext));
            assert!(decrypt(&models, model, &ciphertext, &key(&vector["sessionKeyHex"]), &random(&vector, 0)).is_err());
        }
    }
    #[test]
    fn velin_transfer_fields_decrypt_in_persistent_mail_and_draft_contexts() {
        use crate::entities::entity_facade::EntityFacade;
        use crate::crypto::crypto_facade::ResolvedSessionKey;
        use crate::entities::generated::tutanota::{Mail, MailDetailsDraft};
        use crate::entities::Entity;
        use crate::instance_mapper::InstanceMapper;
        use crate::metamodel::{AttributeId, ModelAssociation};
        fn defaults(models: &TypeModelProvider, model: &TypeModel) -> ParsedEntity {
            let mut out = ParsedEntity::new();
            for v in model.values.values() {
                let value = if v.cardinality == Cardinality::ZeroOrOne { ElementValue::Null }
                    else if v.encrypted { ElementValue::String(String::new()) }
                    else if v.name == "_id" && model.element_type != ElementType::Aggregated {
                        ElementValue::IdTupleGeneratedElementId(crate::IdTupleGenerated::new("list".to_owned().into(), "element".to_owned().into()))
                    } else { match v.value_type {
                        ValueType::GeneratedId => ElementValue::IdGeneratedId("test-id".to_owned().into()),
                        ValueType::CustomId => ElementValue::String("test-id".into()),
                        _ => v.value_type.get_default(),
                    }};
                out.insert(String::from(v.id), value);
            }
            for a in model.associations.values() {
                let items = if a.cardinality == Cardinality::One {
                    if a.association_type == AssociationType::Aggregation {
                        let child = models.resolve_client_type_ref(&TypeRef::new(a.dependency.unwrap_or(model.app), a.ref_type_id)).unwrap();
                        vec![ElementValue::Dict(defaults(models, child))]
                    } else { vec![ElementValue::IdTupleGeneratedElementId(crate::IdTupleGenerated::new("list".to_owned().into(), "element".to_owned().into()))] }
                } else { vec![] };
                out.insert(String::from(a.id), ElementValue::Array(items));
            }
            out
        }
        fn remap(models: &TypeModelProvider, source: &TypeModel, target: &TypeModel, input: &ParsedEntity) -> ParsedEntity {
            let mut out = defaults(models, target);
            for v in source.values.values() {
                let target_id = v.transferred_attribute_id.map(AttributeId::from).or_else(|| {
                    (v.name == "_id" && target.element_type == ElementType::Aggregated)
                        .then(|| target.values.values().find(|v| v.name == "_id").unwrap().id)
                });
                if let Some(id) = target_id { out.insert(String::from(id), input[&String::from(v.id)].clone()); }
            }
            for a in source.associations.values() {
                let target_association: &ModelAssociation = target.associations.values().find(|t| t.name == a.name).unwrap();
                let value = if a.association_type == AssociationType::Aggregation {
                    let child_source = models.resolve_client_type_ref(&TypeRef::new(a.dependency.unwrap_or(source.app), a.ref_type_id)).unwrap();
                    let child_target = models.resolve_client_type_ref(&TypeRef::new(target_association.dependency.unwrap_or(target.app), target_association.ref_type_id)).unwrap();
                    let ElementValue::Array(items) = &input[&String::from(a.id)] else { panic!() };
                    ElementValue::Array(items.iter().map(|item| {
                        let ElementValue::Dict(item) = item else { panic!() };
                        ElementValue::Dict(remap(models, child_source, child_target, item))
                    }).collect())
                } else { input[&String::from(a.id)].clone() };
                out.insert(String::from(target_association.id), value);
            }
            out
        }
        let models = provider(false);
        for vector in fixtures() {
            let (tr, input, params) = inputs(&vector, &provider(true));
            let model = models.resolve_client_type_ref(&tr).unwrap();
            let ciphertext = encrypt(&models, model, &input, &params, &random(&vector, 0)).unwrap();
            let data_a = model.associations.values().find(|a| a.name == "draftData").unwrap();
            let ElementValue::Array(data) = &ciphertext[&String::from(data_a.id)] else { panic!() };
            let ElementValue::Dict(data) = &data[0] else { panic!() };
            let data_model = models.resolve_client_type_ref(&TypeRef::new(AppName::Tutanota, data_a.ref_type_id)).unwrap();
            for (name, target_ref) in [("mail", Mail::type_ref()), ("mailDetailsBlob", MailDetailsDraft::type_ref())] {
                let association = data_model.associations.values().find(|a| a.name == name).unwrap();
                let source = models.resolve_client_type_ref(&TypeRef::new(AppName::Tutanota, association.ref_type_id)).unwrap();
                let target = models.resolve_client_type_ref(&target_ref).unwrap();
                let ElementValue::Array(values) = &data[&String::from(association.id)] else { panic!() };
                let ElementValue::Dict(value) = &values[0] else { panic!() };
                let persistent = remap(&models, source, target, value);
                let facade = EntityFacadeImpl::new(models.clone(), random(&vector, 0));
                let decrypted = facade.decrypt_and_map(target, persistent, ResolvedSessionKey {
                    session_key: key(&vector["sessionKeyHex"]), owner_enc_session_key: const_hex::decode(vector["wrappedSessionKeyHex"].as_str().unwrap()).unwrap(),
                    owner_key_version: vector["ownerKeyVersion"].as_u64().unwrap(), sender_identity_pub_key: None,
                }).unwrap();
                if name == "mail" {
                    let mail: Mail = InstanceMapper::new(models.clone()).parse_entity(decrypted).unwrap();
                    assert_eq!(mail.subject, "Test <draft> λ");
                    assert_eq!(mail.sender.name, "Test Sender");
                } else {
                    let details: MailDetailsDraft = InstanceMapper::new(models.clone()).parse_entity(decrypted).unwrap();
                    assert_eq!(details.details.replyTos[0].address, "reply@example.invalid");
                    assert!(details.details.body.compressedText.as_ref().unwrap().contains("λ"));
                }
            }
        }
    }

}
