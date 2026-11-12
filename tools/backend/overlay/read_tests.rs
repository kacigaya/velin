#[cfg(test)]
mod velin_provider_tests {
    use super::*;
    use crate::crypto::asymmetric_crypto_facade::MockAsymmetricCryptoFacade;
    use crate::crypto::crypto_facade::{MockCryptoFacade, ResolvedSessionKey};
    use crate::entities::entity_facade::MockEntityFacade;
    use crate::entities::generated::tutanota::{MailDetailsBlob, MailDetailsDraft};
    use crate::entity_client::MockEntityClient;
    use crate::key_loader_facade::MockKeyLoaderFacade;
    use crate::util::test_utils::{create_test_entity, mock_type_model_provider};
    use crypto_primitives::aes::Aes256Key;

    async fn check_provider(draft: bool) {
        let provider = Arc::new(mock_type_model_provider());
        let mapper = Arc::new(InstanceMapper::new(provider.clone()));
        let id = crate::IdTupleGenerated::new("details".to_owned().into(), "one".to_owned().into());
        let type_ref = if draft { MailDetailsDraft::type_ref() } else { MailDetailsBlob::type_ref() };
        let model = provider.resolve_client_type_ref(&type_ref).unwrap().clone();
        let raw = if draft {
            mapper.serialize_entity(MailDetailsDraft { _id: Some(id.clone()), ..create_test_entity() }).unwrap()
        } else {
            mapper.serialize_entity(MailDetailsBlob { _id: Some(id.clone()), ..create_test_entity() }).unwrap()
        };
        let mut entity = MockEntityClient::default();
        let model_copy = model.clone();
        entity.expect_resolve_server_type_ref().returning(move |_| Ok(Arc::new(model_copy.clone())));
        if draft {
            let raw = raw.clone();
            entity.expect_load::<crate::IdTupleGenerated>().returning(move |_, _| Ok(raw.clone()));
        } else {
            let raw = raw.clone();
            entity.expect_velin_blob().returning(move |_, _, _| Ok(raw.clone()));
        }
        let key_id = model.get_attribute_id_by_attribute_name("_ownerEncSessionKey").unwrap();
        let version_id = model.get_attribute_id_by_attribute_name("_ownerKeyVersion").unwrap();
        let mut crypto = MockCryptoFacade::default();
        crypto.expect_resolve_session_key().withf(move |raw, _| {
            raw.get(&key_id) == Some(&ElementValue::Bytes(vec![1, 2, 3])) &&
            raw.get(&version_id) == Some(&ElementValue::Number(7))
        }).returning(|_, _| Ok(Some(ResolvedSessionKey {
            session_key: GenericAesKey::Aes256(Aes256Key::from_bytes(&[0; 32]).unwrap()),
            owner_enc_session_key: vec![1, 2, 3], owner_key_version: 7, sender_identity_pub_key: None,
        })));
        let mut facade = MockEntityFacade::default();
        facade.expect_decrypt_and_map().returning(move |_, _, _| Ok(raw.clone()));
        let client = CryptoEntityClient::new(Arc::new(entity), Arc::new(facade), Arc::new(crypto), mapper,
            Arc::new(MockAsymmetricCryptoFacade::default()), Arc::new(MockKeyLoaderFacade::default()));
        let mail = Mail { mailDetailsDraft: if draft { Some(id.clone()) } else { None },
            mailDetails: if draft { None } else { Some(id.clone()) },
            _ownerEncSessionKey: Some(vec![1, 2, 3]), _ownerKeyVersion: Some(7), ..create_test_entity() };
        let access = if draft { None } else { Some(create_test_entity()) };
        assert!(client.velin_details(mail, access).await.is_ok());
    }
    #[tokio::test]
    async fn velin_draft_parent_key_overrides_details_key() { check_provider(true).await; }
    #[tokio::test]
    async fn velin_received_parent_key_overrides_details_key() { check_provider(false).await; }
    async fn file_content(tamper: bool, size: i64) -> Result<Vec<u8>, ApiCallError> {
        use crate::entities::generated::tutanota::TutanotaFile;
        use crate::entities::generated::sys::Blob;
        use crypto_primitives::aes::InitializationVector;
        let provider = Arc::new(mock_type_model_provider());
        let model = provider.resolve_client_type_ref(&TutanotaFile::type_ref()).unwrap().clone();
        let key = GenericAesKey::Aes256(Aes256Key::from_bytes(&[7; 32]).unwrap());
        let mut encrypted = key.encrypt_data(b"attachment bytes", InitializationVector::from_bytes(&[1; 16]).unwrap()).unwrap();
        if tamper { encrypted[20] ^= 1; }
        let mut entity = MockEntityClient::default();
        entity.expect_resolve_server_type_ref().returning(move |_| Ok(Arc::new(model.clone())));
        if size <= 20 * 1024 * 1024 {
            entity.expect_velin_file_blobs().returning(move |_, _| Ok(vec![encrypted.clone()]));
        }
        let mut crypto = MockCryptoFacade::default();
        crypto.expect_resolve_session_key().returning(move |_, _| Ok(Some(ResolvedSessionKey {
            session_key: key.clone(), owner_enc_session_key: vec![1], owner_key_version: 0, sender_identity_pub_key: None,
        })));
        let client = CryptoEntityClient::new(Arc::new(entity), Arc::new(MockEntityFacade::default()), Arc::new(crypto),
            Arc::new(InstanceMapper::new(provider)), Arc::new(MockAsymmetricCryptoFacade::default()), Arc::new(MockKeyLoaderFacade::default()));
        let file = TutanotaFile { size, blobs: vec![Blob { size: 100, ..create_test_entity() }], ..create_test_entity() };
        client.velin_file_content(file, create_test_entity()).await
    }
    #[tokio::test]
    async fn velin_file_decrypts_and_rejects_tampering_and_size_mismatch() {
        assert_eq!(file_content(false, 16).await.unwrap(), b"attachment bytes");
        // Files of external mail carry the encrypted size: version byte, IV, padded data and MAC.
        assert_eq!(file_content(false, 1 + 16 + 32 + 32).await.unwrap(), b"attachment bytes");
        assert!(file_content(true, 16).await.is_err());
        assert!(file_content(false, 15).await.is_err());
        assert!(file_content(false, 20 * 1024 * 1024 + 1).await.is_err());
    }

}
