
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
            let wrapped = params.owner_key.object.encrypt_key(&session_key, InitializationVector::generate(&random(&vector, 0)));
            assert_eq!(const_hex::encode(wrapped), vector["wrappedSessionKeyHex"].as_str().unwrap());
            let ciphertext = encrypt(&models, model, &input, &params, &random(&vector, vector["encryptionRandomOffset"].as_u64().unwrap() as usize)).unwrap();
            let raw = JsonSerializer::new(models.clone()).serialize(&tr, ciphertext.clone()).unwrap();
            assert_eq!(String::from_utf8(wire_json(&raw).unwrap()).unwrap(), vector["wireJson"].as_str().unwrap(), "{} {}", vector["scheme"], vector["operation"]);
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
}
