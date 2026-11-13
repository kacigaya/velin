
#[cfg(test)]
mod velin_entity_patch_tests {
    use super::*;
    use crate::bindings::{file_client::MockFileClient, rest_client::MockRestClient};
    use crate::crypto::crypto_facade::ResolvedSessionKey;
    use crate::entities::entity_facade::{EntityFacade, EntityFacadeImpl};
    use crate::entities::Entity;
    use crate::instance_mapper::InstanceMapper;
    use crate::json_serializer::JsonSerializer;
    use crate::metamodel::AppName;
    use crate::type_model_provider::TypeModelProvider;
    use crate::TypeRef;
    use crypto_primitives::versioned::Versioned;
    use rand_core::{CryptoRng, RngCore};
    use std::sync::Arc;

    // tools/backend/vectors/patches.ts: SHA-256(seed || big-endian u32 counter), from a byte offset.
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

    fn vectors() -> Vec<serde_json::Value> {
        serde_json::from_str::<serde_json::Value>(include_str!("velin_patches.json")).unwrap()["vectors"].as_array().unwrap().clone()
    }
    fn hex_key(value: &serde_json::Value) -> GenericAesKey {
        GenericAesKey::from_bytes(&const_hex::decode(value.as_str().unwrap()).unwrap()).unwrap()
    }
    fn models() -> Arc<TypeModelProvider> {
        Arc::new(TypeModelProvider::new_test(Arc::new(MockRestClient::default()), Arc::new(MockFileClient::default()), "https://example.invalid".into()))
    }
    fn mail_ref() -> TypeRef { TypeRef::new(AppName::Tutanota, 97u64.into()) }
    fn key(vector: &serde_json::Value) -> VelinValueKey {
        if vector["scheme"] == "aead" {
            VelinValueKey::Instance {
                group_key: Versioned::new(hex_key(&vector["currentGroupKeyHex"]), vector["currentGroupKeyVersion"].as_u64().unwrap()),
                kdf_nonce: const_hex::decode(vector["kdfNonceHex"].as_str().unwrap()).unwrap(),
            }
        } else { VelinValueKey::Session(hex_key(&vector["sessionKeyHex"])) }
    }
    fn number(vector: &serde_json::Value, name: &str) -> ElementValue {
        ElementValue::Number(vector[name].as_str().unwrap().parse().unwrap())
    }
    fn attribute(vector: &serde_json::Value) -> &str { vector["attribute"].as_str().unwrap() }
    fn instance_key_case(vector: &serde_json::Value) -> bool { vector["scheme"] == "aead" && vector["field"] == "replyType" }

    #[test]
    fn velin_patch_vectors_match_upstream_wire_bytes() {
        let models = models();
        let model = models.resolve_client_type_ref(&mail_ref()).unwrap();
        assert_eq!(model.get_attribute_id_by_attribute_name("replyType").unwrap(), "466");
        assert_eq!(model.get_attribute_id_by_attribute_name("phishingStatus").unwrap(), "1021");
        for vector in vectors() {
            let attribute = attribute(&vector);
            assert_eq!(model.get_attribute_id_by_attribute_name(vector["field"].as_str().unwrap()).unwrap(), attribute);
            let body: serde_json::Value = serde_json::from_str(vector["body"].as_str().unwrap()).unwrap();
            let expected = body["2616"][0]["2571"].as_str().unwrap();
            let random = RandomizerFacade::from_core(TestRng::new(vector["seed"].as_str().unwrap(),
                vector["valueRandomOffset"].as_u64().unwrap_or(0) as usize));
            if !is_encrypted(model, attribute).unwrap() {
                // A plain value needs no key and is sent as it is.
                let value = patch_value(model, attribute, &number(&vector, "to"), None, &random).unwrap();
                assert_eq!(value, expected);
                let raw = JsonSerializer::new(models.clone()).serialize(&PatchList::type_ref(),
                    InstanceMapper::new(models.clone()).serialize_entity(patch_list(attribute, value, &random)).unwrap()).unwrap();
                let mut wire = serde_json::to_value(raw).unwrap();
                wire["2615"] = body["2615"].clone();
                wire["2616"][0]["2568"] = body["2616"][0]["2568"].clone();
                assert_eq!(wire, body);
                continue;
            }
            assert!(patch_value(model, attribute, &number(&vector, "to"), None, &random).is_err(), "an encrypted value needs a key");
            let value = patch_value(model, attribute, &number(&vector, "to"), Some(&key(&vector)), &random).unwrap();
            assert_eq!(value, expected, "{} {}", vector["scheme"], vector["seed"]);
            let ciphertext = BASE64_STANDARD.decode(&value).unwrap();

            // Same wire JSON as upstream, apart from the random aggregate IDs.
            let patch = patch_list(attribute, value, &random);
            let parsed = InstanceMapper::new(models.clone()).serialize_entity(patch).unwrap();
            let raw = JsonSerializer::new(models.clone()).serialize(&PatchList::type_ref(), parsed).unwrap();
            let mut wire = serde_json::to_value(raw).unwrap();
            assert_eq!(wire["2615"].as_str().unwrap().len(), 6);
            assert_eq!(wire["2616"][0]["2568"].as_str().unwrap().len(), 6);
            wire["2615"] = body["2615"].clone();
            wire["2616"][0]["2568"] = body["2616"][0]["2568"].clone();
            assert_eq!(wire, body);

            match key(&vector) {
                VelinValueKey::Instance { group_key, kdf_nonce } => {
                    assert_eq!(instance_key_version(&ciphertext), Some(group_key.version));
                    assert_eq!(decrypt_instance_value(model, "466", &ciphertext, &group_key, &kdf_nonce).unwrap(), number(&vector, "to"));
                    // The value is bound to its attribute, key and content.
                    assert!(decrypt_instance_value(model, "105", &ciphertext, &group_key, &kdf_nonce).is_err());
                    let mut altered = ciphertext.clone();
                    *altered.last_mut().unwrap() ^= 1;
                    assert!(decrypt_instance_value(model, "466", &altered, &group_key, &kdf_nonce).is_err());
                    let mut nonce = kdf_nonce.clone();
                    nonce[0] ^= 1;
                    assert!(decrypt_instance_value(model, "466", &ciphertext, &group_key, &nonce).is_err());
                    assert!(decrypt_instance_value(model, "466", &ciphertext, &group_key, &kdf_nonce[1..]).is_err());
                },
                VelinValueKey::Session(_) => assert_eq!(instance_key_version(&ciphertext), None),
            }
        }
    }

    // The release decrypts every value with the session key and fails the whole mail on an AEAD value.
    #[test]
    fn velin_patched_mail_reads_back_with_mixed_ciphers() {
        let models = models();
        let model = models.resolve_client_type_ref(&mail_ref()).unwrap();
        for vector in vectors() {
            let raw = serde_json::from_str(vector["patchedMail"].as_str().unwrap()).unwrap();
            let mut parsed = JsonSerializer::new(models.clone()).parse(&mail_ref(), raw).unwrap();
            let (found, context) = instance_key_values(model, &parsed);
            let facade = EntityFacadeImpl::new(models.clone(), RandomizerFacade::from_core(rand_core::OsRng));
            let session_key = || ResolvedSessionKey { session_key: hex_key(&vector["sessionKeyHex"]),
                owner_enc_session_key: vec![], owner_key_version: 3, sender_identity_pub_key: None };
            let mut values = vec![];
            if instance_key_case(&vector) {
                assert!(facade.decrypt_and_map(model, parsed.clone(), session_key()).is_err(), "the release fails the whole mail");
                assert_eq!(found, vec![("466".to_owned(), 5)]);
                let (group, nonce) = context.unwrap();
                assert_eq!(group.as_str(), "test-mail-group");
                let VelinValueKey::Instance { group_key, .. } = key(&vector) else { unreachable!() };
                let Some(ElementValue::Bytes(ciphertext)) = parsed.get("466") else { panic!() };
                values.push(("466".to_owned(), decrypt_instance_value(model, "466", ciphertext, &group_key, &nonce).unwrap()));
                blank_instance_values(&mut parsed, &values);
            } else {
                assert!(found.is_empty() && context.is_none());
            }
            let mut decrypted = facade.decrypt_and_map(model, parsed, session_key()).unwrap();
            apply_instance_values(&mut decrypted, values);
            assert!(!decrypted.contains_key("_errors"));
            assert_eq!(decrypted.get("105"), Some(&ElementValue::String("Original λ".into())));
            assert_eq!(decrypted.get(attribute(&vector)), Some(&number(&vector, "to")));
        }
    }
}
