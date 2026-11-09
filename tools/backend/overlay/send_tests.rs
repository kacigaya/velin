#[cfg(test)]
mod velin_send_tests {
    use super::*;
    use crate::entities::generated::{sys::{PublicKeyGetOut, PublicKeySignature, IdentityKeyGetOut}, tutanota::{InternalRecipientKeyData, SendDraftData}};
    use crate::velin_session_transfer::velin_session_transfer_tests::{TestRng, provider};
    use crypto_primitives::{aes::InitializationVector, key::GenericAesKey, randomizer_facade::RandomizerFacade};

    #[test]
    fn velin_send_vectors_match_release_crypto_and_every_wire_byte() {
        let fixtures: serde_json::Value = serde_json::from_str(include_str!("velin_sends.json")).unwrap();
        for vector in fixtures["vectors"].as_array().unwrap() {
            let bytes = |name: &str| const_hex::decode(vector[name].as_str().unwrap()).unwrap();
            let random = RandomizerFacade::from_core(TestRng::new(vector["seed"].as_str().unwrap(), 0));
            let bucket = GenericAesKey::from_bytes(&bytes("bucketKeyHex")).unwrap();
            let session = GenericAesKey::from_bytes(&bytes("sessionKeyHex")).unwrap();
            let wrapped = bucket.encrypt_key(&session, InitializationVector::generate(&random));
            assert_eq!(wrapped, bytes("wrappedSessionKeyHex"));
            let public = crate::crypto::rsa::RSAPublicKey::deserialize(&bytes("publicKeyHex")).unwrap();
            let encrypted = public.encrypt(&random, bucket.as_bytes()).unwrap();
            assert_eq!(encrypted, bytes("encryptedBucketHex"));
            let signed = PublicKeyGetOut { _format: 0, pubKeyVersion: 0, pubRsaKey: Some(bytes("publicKeyHex")), pubEccKey: None, pubKyberKey: None,
                signature: Some(PublicKeySignature { _id: None, signature: bytes("signatureHex"), signingKeyVersion: 0, signatureType: 2, publicKeyVersion: 0 }) };
            assert_eq!(velin_public_key_message(&signed).unwrap(), bytes("canonicalHex"));
            let identity = IdentityKeyGetOut { _format: 0, publicIdentityKey: bytes("identityKeyHex"), publicIdentityKeyVersion: 0 };
            assert_eq!(velin_verify_recipient_key(&signed, Some(&identity)).unwrap(), 1);
            let data = velin_send_data(IdTupleGenerated::new("test-mail-list".to_owned().into(), "test-draft".to_owned().into()), wrapped,
                vec![InternalRecipientKeyData { _id: None, mailAddress: "self@tuta.com".into(), pubEncBucketKey: encrypted,
                    recipientKeyVersion: 0, senderKeyVersion: None, protocolVersion: 0 }], false, None);
            let models = provider(false);
            let mapper = crate::instance_mapper::InstanceMapper::new(models.clone());
            let parsed = mapper.serialize_entity(data).unwrap();
            let model = models.resolve_client_type_ref(&SendDraftData::type_ref()).unwrap();
            let mapped = crate::velin_session_transfer::assign_ids(&models, model, &parsed, &random).unwrap();
            let wire = crate::json_serializer::JsonSerializer::new(models).serialize(&SendDraftData::type_ref(), mapped).unwrap();
            assert_eq!(crate::velin_session_transfer::wire_json(&wire).unwrap(), vector["wireJson"].as_str().unwrap().as_bytes());
        }
    }
}

#[cfg(test)]
mod velin_send_preflight_tests {
    use super::*;
    use crate::crypto_entity_client::MockCryptoEntityClient;
    use crate::entities::generated::{sys::{User, GroupMembership}, tutanota::{ConversationEntry, MailAddress, MailDetails, Recipients}};
    use crate::services::{service_executor::MockResolvingServiceExecutor, generated::{sys::PublicKeyService, tutanota::SendDraftService}};
    use crate::user_facade::MockUserFacade;
    use crate::util::test_utils::create_test_entity;

    struct Trust;
    #[async_trait::async_trait]
    impl VelinIdentityTrustStore for Trust {
        async fn load(&self, _: String) -> Result<Option<VelinTrustedIdentity>, ApiCallError> { Ok(None) }
        async fn trust_on_first_use(&self, _: String, identity: VelinTrustedIdentity) -> Result<VelinTrustedIdentity, ApiCallError> { Ok(identity) }
    }
    fn id(name: &str) -> IdTupleGenerated { IdTupleGenerated::new("list".to_owned().into(), name.to_owned().into()) }
    fn draft() -> Mail {
        Mail { _id: Some(id("draft")), mailDetailsDraft: Some(id("details")), confidential: true, method: 0, replyType: 0,
            _ownerGroup: Some("group".to_owned().into()), conversationEntry: id("conversation"),
            sender: MailAddress { address: "self@tuta.com".into(), ..create_test_entity() }, ..create_test_entity() }
    }
    fn sender(client: &mut MockCryptoEntityClient, mail: &Mail) {
        let entry = mail.conversationEntry.clone();
        client.expect_load::<ConversationEntry, IdTupleGenerated>().returning(move |_| Ok(ConversationEntry {
            _id: Some(entry.clone()), conversationType: 0, previous: None, ..create_test_entity() }));
        client.expect_load::<Group, GeneratedId>().returning(|id| Ok(Group { _id: Some(id.clone()), user: Some("user".to_owned().into()), ..create_test_entity() }));
        client.expect_load::<GroupInfo, IdTupleGenerated>().returning(|id| Ok(GroupInfo { _id: Some(id.clone()), mailAddress: Some("self@tuta.com".into()), ..create_test_entity() }));
    }
    fn user() -> MockUserFacade {
        let mut user = MockUserFacade::default();
        user.expect_get_user().return_const(Arc::new(User { _id: Some("user".to_owned().into()), memberships: vec![GroupMembership {
            group: "group".to_owned().into(), groupType: Some(GroupType::Mail as i64), ..create_test_entity() }], ..create_test_entity() }));
        user
    }
    struct StoredTrust(VelinTrustedIdentity);
    #[async_trait::async_trait]
    impl VelinIdentityTrustStore for StoredTrust {
        async fn load(&self, _: String) -> Result<Option<VelinTrustedIdentity>, ApiCallError> { Ok(Some(self.0.clone())) }
        async fn trust_on_first_use(&self, _: String, _: VelinTrustedIdentity) -> Result<VelinTrustedIdentity, ApiCallError> {
            panic!("Stored identity must not be replaced")
        }
    }
    #[tokio::test]
    async fn velin_stored_identity_verifies_fresh_keys_without_identity_refetch() {
        use crate::entities::generated::sys::{PublicKeyGetOut, PublicKeySignature};
        use crate::services::generated::sys::IdentityKeyService;
        let fixtures: serde_json::Value = serde_json::from_str(include_str!("velin_sends.json")).unwrap();
        let vector = &fixtures["vectors"][0];
        let bytes = |name: &str| const_hex::decode(vector[name].as_str().unwrap()).unwrap();
        for (manual, mismatch, expected) in [(false, false, 3), (true, false, 4), (true, true, 2)] {
            let key = PublicKeyGetOut { _format: 0, pubKeyVersion: 0, pubRsaKey: Some(bytes("publicKeyHex")),
                pubEccKey: None, pubKyberKey: None, signature: if mismatch { None } else { Some(PublicKeySignature {
                    _id: None, signature: bytes("signatureHex"), signingKeyVersion: 0, signatureType: 2, publicKeyVersion: 0 }) } };
            let mut services = MockResolvingServiceExecutor::default();
            services.expect_get::<PublicKeyService>().times(2).returning(move |_, _| Ok(key.clone()));
            services.expect_get::<IdentityKeyService>().never();
            let facade = MailFacade::new(Arc::new(MockCryptoEntityClient::default()), Arc::new(MockUserFacade::default()), Arc::new(services));
            facade.velin_set_identity_trust_store(Arc::new(StoredTrust(VelinTrustedIdentity {
                key: bytes("identityKeyHex"), version: 0, manual,
            })));
            for _ in 0..2 {
                let result = facade.velin_resolve_recipient("self@tuta.com".into()).await.unwrap();
                assert!(result.internal);
                assert_eq!(result.key_state, expected);
            }
        }
    }
    #[tokio::test]
    async fn velin_send_rejects_excluded_drafts_before_lookup_or_post() {
        for reason in [1, 2, 3] {
            let mut mail = draft();
            match reason { 1 => mail.sendAt = Some(crate::date::DateTime::from_millis(1)), 2 => mail.confidential = false, _ => mail.attachments = vec![id("file")] }
            let mut client = MockCryptoEntityClient::default();
            client.expect_velin_draft_mail().return_once(move |_| Ok(mail));
            let facade = MailFacade::new(Arc::new(client), Arc::new(MockUserFacade::default()), Arc::new(MockResolvingServiceExecutor::default()));
            assert!(matches!(facade.velin_send_draft(id("draft")).await, Err(ApiCallError::VelinSendUnsupported { reason: actual }) if actual == reason));
        }
    }
    #[tokio::test]
    async fn velin_send_missing_and_external_recipients_never_post() {
        for (address, unsupported) in [("missing@tuta.com", false), ("outside@example.invalid", true)] {
            let mail = draft();
            let mut client = MockCryptoEntityClient::default();
            sender(&mut client, &mail);
            client.expect_velin_draft_mail().return_once(move |_| Ok(mail));
            let address = address.to_owned();
            client.expect_velin_draft_details().return_once(move |_| Ok(MailDetails {
                recipients: Recipients { toRecipients: vec![MailAddress { address, ..create_test_entity() }], ..create_test_entity() },
                replyTos: vec![], ..create_test_entity() }));
            let mut services = MockResolvingServiceExecutor::default();
            services.expect_get::<PublicKeyService>().return_once(|_, _| Err(ApiCallError::ServerResponseError { source: HttpError::NotFoundError }));
            services.expect_post::<SendDraftService>().never();
            let facade = MailFacade::new(Arc::new(client), Arc::new(user()), Arc::new(services));
            facade.velin_set_identity_trust_store(Arc::new(Trust));
            let result = facade.velin_send_draft(id("draft")).await;
            if unsupported { assert!(matches!(result, Err(ApiCallError::VelinSendUnsupported { reason: 6 }))) }
            else { assert!(matches!(result, Err(ApiCallError::VelinSendBlocked { reason: 0, indices }) if indices == vec![0])) }
        }
    }
    #[tokio::test]
    async fn velin_current_lookup_absence_and_rate_limits_remain_distinct() {
        for address in ["absent@tuta.com", "external@example.invalid"] {
            let mut services = MockResolvingServiceExecutor::default();
            services.expect_get::<PublicKeyService>().times(2).returning(|_, _| Err(ApiCallError::ServerResponseError { source: HttpError::NotFoundError }));
            let facade = MailFacade::new(Arc::new(MockCryptoEntityClient::default()), Arc::new(MockUserFacade::default()), Arc::new(services));
            for _ in 0..2 { assert_eq!(facade.velin_resolve_recipient(address.into()).await.unwrap().internal, address.ends_with("@tuta.com")); }
        }
        let mut services = MockResolvingServiceExecutor::default();
        services.expect_get::<PublicKeyService>().return_once(|_, _| Err(ApiCallError::ServerResponseError {
            source: HttpError::TooManyRequestsError { suspension_time_sec: None } }));
        let facade = MailFacade::new(Arc::new(MockCryptoEntityClient::default()), Arc::new(MockUserFacade::default()), Arc::new(services));
        assert!(matches!(facade.velin_resolve_recipient("self@tuta.com".into()).await, Err(ApiCallError::VelinRecipientRateLimited)));
    }
    #[tokio::test]
    async fn velin_send_mismatched_signature_never_posts() {
        use crate::entities::generated::sys::{PublicKeyGetOut, PublicKeySignature};
        use crate::services::generated::sys::IdentityKeyService;
        let mail = draft();
        let mut client = MockCryptoEntityClient::default();
        sender(&mut client, &mail);
        client.expect_velin_draft_mail().return_once(move |_| Ok(mail));
        client.expect_velin_draft_details().return_once(move |_| Ok(MailDetails {
            recipients: Recipients { toRecipients: vec![MailAddress { address: "self@tuta.com".into(), ..create_test_entity() }], ..create_test_entity() },
            replyTos: vec![], ..create_test_entity() }));
        let mut services = MockResolvingServiceExecutor::default();
        services.expect_get::<PublicKeyService>().return_once(|_, _| Ok(PublicKeyGetOut {
            _format: 0, pubKeyVersion: 0, pubRsaKey: Some(crypto_primitives::compatibility_test_utils::get_compatibility_test_data().rsa_encryption_tests.remove(0).public_key),
            pubEccKey: None, pubKyberKey: None, signature: Some(PublicKeySignature {
                _id: None, signature: vec![0; 64], signingKeyVersion: 0, signatureType: 2, publicKeyVersion: 0 }) }));
        services.expect_get::<IdentityKeyService>().return_once(|_, _| Err(ApiCallError::ServerResponseError { source: HttpError::NotFoundError }));
        services.expect_post::<SendDraftService>().never();
        let facade = MailFacade::new(Arc::new(client), Arc::new(user()), Arc::new(services));
        facade.velin_set_identity_trust_store(Arc::new(Trust));
        assert!(matches!(facade.velin_send_draft(id("draft")).await,
            Err(ApiCallError::VelinSendBlocked { reason: 1, indices }) if indices == vec![0]));
    }

    #[tokio::test]
    async fn velin_send_legacy_identity_is_allowed_and_posts_only_ciphertext() {
        use crate::entities::generated::{sys::PublicKeyGetOut, tutanota::{InternalRecipientKeyData, SendDraftReturn}};
        use crate::services::generated::sys::IdentityKeyService;
        use crypto_primitives::key::GenericAesKey;
        let mail = draft();
        let mut client = MockCryptoEntityClient::default();
        sender(&mut client, &mail);
        client.expect_velin_draft_mail().return_once(move |_| Ok(mail));
        client.expect_velin_draft_details().return_once(move |_| Ok(MailDetails {
            recipients: Recipients { toRecipients: vec![MailAddress { address: "self@tuta.com".into(), ..create_test_entity() }], ..create_test_entity() },
            replyTos: vec![], ..create_test_entity() }));
        client.expect_velin_send_plaintext_setting().return_once(|_| Ok(false));
        client.expect_velin_send_mail_key().return_once(|_| Ok(GenericAesKey::from_bytes(&[19; 32]).unwrap()));
        client.expect_velin_send_recipient_key().times(1).return_once(|_, bucket, _, address| {
            assert_eq!(bucket.as_bytes().len(), 32);
            Ok(InternalRecipientKeyData { _id: None, mailAddress: address, pubEncBucketKey: vec![11; 256],
                recipientKeyVersion: 0, senderKeyVersion: None, protocolVersion: 0 })
        });
        let mut services = MockResolvingServiceExecutor::default();
        services.expect_get::<PublicKeyService>().return_once(|_, _| Ok(PublicKeyGetOut {
            _format: 0, pubKeyVersion: 0, pubRsaKey: Some(crypto_primitives::compatibility_test_utils::get_compatibility_test_data().rsa_encryption_tests.remove(0).public_key),
            pubEccKey: None, pubKyberKey: None, signature: None }));
        services.expect_get::<IdentityKeyService>().return_once(|_, _| Err(ApiCallError::ServerResponseError { source: HttpError::NotFoundError }));
        services.expect_post::<SendDraftService>().times(1).return_once(|data, params| {
            assert!(params.velin_send);
            assert!(data.mailSessionKey.is_none());
            assert!(data.bucketEncMailSessionKey.as_ref().unwrap().len() > 32);
            assert_eq!(data.internalRecipientKeyData.len(), 1);
            assert!(data.sessionEncEncryptionAuthStatus.is_none());
            let nested = data.parameters.unwrap();
            assert!(nested.mailSessionKey.is_none());
            assert_eq!(nested.bucketEncMailSessionKey, data.bucketEncMailSessionKey);
            assert!(!data.allowUndo && data.sendAt.is_none());
            Ok(SendDraftReturn { ..create_test_entity() })
        });
        let facade = MailFacade::new(Arc::new(client), Arc::new(user()), Arc::new(services));
        facade.velin_set_identity_trust_store(Arc::new(Trust));
        facade.velin_send_draft(id("draft")).await.unwrap();
    }

}
