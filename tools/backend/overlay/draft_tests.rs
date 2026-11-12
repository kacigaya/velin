
#[cfg(test)]
mod velin_draft_tests {
    use super::*;
    use crate::crypto_entity_client::MockCryptoEntityClient;
    use crate::entities::generated::sys::{User, GroupMembership};
    use crate::entities::generated::tutanota::*;
    use crate::services::service_executor::MockResolvingServiceExecutor;
    use crate::services::generated::tutanota::DraftService;
    use crate::user_facade::{MockUserFacade, VelinEncryptionScheme};
    use crate::util::test_utils::create_test_entity;
    use crypto_primitives::{key::GenericAesKey, versioned::Versioned};

    fn id(value: &str) -> IdTupleGenerated { IdTupleGenerated::new("list".to_owned().into(), value.to_owned().into()) }
    fn input(updating: bool) -> VelinDraftInput {
        VelinDraftInput { id: updating.then(|| id("draft")), sender: VelinSender { address: "sender@example.invalid".into(), name: "Sender".into() },
            to: vec![VelinSender { address: "recipient@example.invalid".into(), name: "Recipient".into() }], cc: vec![], bcc: vec![],
            subject: "Draft subject".into(), html: "<b>Draft</b>".into(), confidential: true,
            attachments: if updating { vec![id("retained")] } else { vec![] }, previous: None, conversation_type: 0 }
    }
    fn mail(input: &VelinDraftInput) -> Mail {
        Mail { _id: Some(id("draft")), _ownerGroup: Some("group".to_owned().into()), _ownerEncSessionKey: Some(vec![17; 80]),
            _ownerKeyVersion: Some(3), _kdfNonce: Some(vec![5; 16]), mailDetailsDraft: Some(id("details")), method: 1,
            sender: MailAddress { name: input.sender.name.clone(), address: input.sender.address.clone(), ..create_test_entity() },
            subject: input.subject.clone(), confidential: input.confidential, attachments: input.attachments.clone(), ..create_test_entity() }
    }
    fn details(input: &VelinDraftInput) -> MailDetails {
        MailDetails { body: Body { text: Some(String::new()), compressedText: Some(input.html.clone()), ..create_test_entity() },
            recipients: Recipients { toRecipients: input.to.iter().map(|a| MailAddress { address: a.address.clone(), name: a.name.clone(), ..create_test_entity() }).collect(),
                ccRecipients: vec![], bccRecipients: vec![], ..create_test_entity() },
            replyTos: vec![EncryptedMailAddress { address: "reply@example.invalid".into(), name: "Reply".into(), ..create_test_entity() }], ..create_test_entity() }
    }
    fn user() -> MockUserFacade {
        let user = User { _id: Some("user".to_owned().into()), memberships: vec![GroupMembership { group: "group".to_owned().into(), groupType: Some(GroupType::Mail as i64), ..create_test_entity() }], ..create_test_entity() };
        let mut mock = MockUserFacade::default();
        mock.expect_get_user().return_const(Arc::new(user));
        mock.expect_velin_encryption_scheme().return_const(Some(VelinEncryptionScheme::Aead));
        mock
    }
    fn sender(client: &mut MockCryptoEntityClient) {
        client.expect_load::<Group, GeneratedId>().returning(|id| Ok(Group { _id: Some(id.clone()), user: Some("user".to_owned().into()), ..create_test_entity() }));
        client.expect_load::<GroupInfo, IdTupleGenerated>().returning(|id| Ok(GroupInfo { _id: Some(id.clone()), mailAddress: Some("sender@example.invalid".into()), ..create_test_entity() }));
    }

    #[tokio::test]
    async fn velin_draft_create_uses_post_and_current_owner_key() {
        let input = input(false);
        let mut client = MockCryptoEntityClient::default();
        sender(&mut client);
        client.expect_velin_draft_owner_key().withf(|group, version| group.as_str() == "group" && version.is_none())
            .returning(|_, _| Ok(Versioned::new(GenericAesKey::from_bytes(&[1; 32]).unwrap(), 7)));
        let mail = mail(&input);
        client.expect_velin_draft_mail().withf(|target| target == &id("draft")).returning(move |_| Ok(mail.clone()));
        let expected_details = details(&input);
        client.expect_velin_draft_details().returning(move |_| Ok(expected_details.clone()));
        let mut services = MockResolvingServiceExecutor::default();
        services.expect_post::<DraftService>().withf(|data, params| {
            let mail = data.draftData.mail.as_ref().unwrap();
            let details = data.draftData.mailDetailsBlob.as_ref().unwrap();
            data.previousMessageId.is_none() && data.conversationType == 0 && data.ownerEncSessionKey.is_none() && data.ownerKeyVersion.is_none() &&
                data.draftData.subject.is_none() && data.draftData.toRecipients.is_empty() && data.draftData.addedAttachments.is_empty() &&
                mail._ownerKeyVersion == Some(7) && mail._ownerEncSessionKey == details._ownerEncSessionKey && mail._kdfNonce.is_none() &&
                mail.subject == "Draft subject" && mail.confidential && mail.method == 0 && mail.firstRecipient.as_ref().unwrap().address == "recipient@example.invalid" &&
                details.details.body.compressedText.as_deref() == Some("<b>Draft</b>") &&
                params.velin_session_transfer.as_ref().is_some_and(|p| p.scheme == VelinEncryptionScheme::Aead && p.owner_key.version == 7)
        }).return_once(|_, _| Ok(DraftCreateReturn { _format: 0, draft: id("draft") }));
        let facade = MailFacade::new(Arc::new(client), Arc::new(user()), Arc::new(services));
        assert_eq!(facade.velin_save_draft(input).await.unwrap(), id("draft"));
    }

    #[tokio::test]
    async fn velin_draft_update_reuses_key_nonce_method_and_reply_to() {
        let input = input(true);
        let mut client = MockCryptoEntityClient::default();
        sender(&mut client);
        client.expect_velin_draft_owner_key().withf(|group, version| group.as_str() == "group" && *version == Some(3))
            .returning(|_, _| Ok(Versioned::new(GenericAesKey::from_bytes(&[1; 32]).unwrap(), 3)));
        let mut initial = mail(&input);
        initial.attachments.push(id("removed"));
        let updated = mail(&input);
        let mut calls = 0;
        client.expect_velin_draft_mail().returning(move |_| { calls += 1; Ok(if calls == 1 { initial.clone() } else { updated.clone() }) });
        let expected_details = details(&input);
        client.expect_velin_draft_details().returning(move |_| Ok(expected_details.clone()));
        let mut services = MockResolvingServiceExecutor::default();
        services.expect_put::<DraftService>().withf(|data, params| {
            let mail = data.draftData.mail.as_ref().unwrap();
            let details = data.draftData.mailDetailsBlob.as_ref().unwrap();
            data.draft == id("draft") && data.draftData.removedAttachments == vec![id("removed")] && data.draftData.addedAttachments.is_empty() &&
                mail._ownerKeyVersion == Some(3) && mail._ownerEncSessionKey == Some(vec![17; 80]) && mail._kdfNonce == Some(vec![5; 16]) &&
                mail.method == 1 && details._ownerEncSessionKey == mail._ownerEncSessionKey && details._kdfNonce == mail._kdfNonce &&
                details.details.replyTos[0].address == "reply@example.invalid" &&
                params.velin_session_transfer.as_ref().is_some_and(|p| p.owner_key.version == 3 && p.scheme == VelinEncryptionScheme::Aead)
        }).return_once(|_, _| Ok(DraftUpdateReturn { _format: 0, ..create_test_entity() }));
        let facade = MailFacade::new(Arc::new(client), Arc::new(user()), Arc::new(services));
        assert_eq!(facade.velin_save_draft(input).await.unwrap(), id("draft"));
    }

    #[tokio::test]
    async fn velin_draft_rejects_new_attachments_and_mailbox_changes_before_put() {
        for other_group in [true, false] {
            let input = input(true);
            let mut existing = mail(&input);
            if other_group { existing._ownerGroup = Some("other".to_owned().into()) } else { existing.attachments.clear() }
            let mut client = MockCryptoEntityClient::default();
            sender(&mut client);
            client.expect_velin_draft_mail().returning(move |_| Ok(existing.clone()));
            let facade = MailFacade::new(Arc::new(client), Arc::new(user()), Arc::new(MockResolvingServiceExecutor::default()));
            assert!(facade.velin_save_draft(input).await.is_err());
        }
    }
    #[tokio::test]
    async fn velin_reply_create_sends_previous_message_id() {
        for (found, expected) in [(true, Some("<message@example.invalid>")), (false, None)] {
            let mut input = input(false);
            input.previous = Some(id("previous"));
            input.conversation_type = 1;
            let mut client = MockCryptoEntityClient::default();
            sender(&mut client);
            client.expect_load::<Mail, IdTupleGenerated>().returning(move |target| if found {
                Ok(Mail { _id: Some(target.clone()), conversationEntry: id("entry"), ..create_test_entity() })
            } else { Err(ApiCallError::ServerResponseError { source: HttpError::NotFoundError }) });
            client.expect_load::<ConversationEntry, IdTupleGenerated>().returning(|target| Ok(ConversationEntry {
                _id: Some(target.clone()), messageId: "<message@example.invalid>".into(), ..create_test_entity() }));
            client.expect_velin_draft_owner_key().returning(|_, _| Ok(Versioned::new(GenericAesKey::from_bytes(&[1; 32]).unwrap(), 7)));
            let mail = mail(&input);
            client.expect_velin_draft_mail().returning(move |_| Ok(mail.clone()));
            let expected_details = details(&input);
            client.expect_velin_draft_details().returning(move |_| Ok(expected_details.clone()));
            let mut services = MockResolvingServiceExecutor::default();
            services.expect_post::<DraftService>().withf(move |data, _| data.conversationType == 1 && data.previousMessageId.as_deref() == expected)
                .return_once(|_, _| Ok(DraftCreateReturn { _format: 0, draft: id("draft") }));
            let facade = MailFacade::new(Arc::new(client), Arc::new(user()), Arc::new(services));
            assert_eq!(facade.velin_save_draft(input).await.unwrap(), id("draft"));
        }
    }
    #[tokio::test]
    async fn velin_draft_rejects_inconsistent_conversation_before_any_request() {
        for (previous, kind) in [(true, 0), (false, 1), (true, 3), (true, -1)] {
            let mut input = input(false);
            input.previous = previous.then(|| id("previous"));
            input.conversation_type = kind;
            let facade = MailFacade::new(Arc::new(MockCryptoEntityClient::default()), Arc::new(user()), Arc::new(MockResolvingServiceExecutor::default()));
            assert!(facade.velin_save_draft(input).await.is_err());
        }
    }
    #[test]
    fn velin_draft_update_preserves_implicit_legacy_version() {
        let data = velin_draft_data(&input(true), vec![7; 16], None, None, 0, vec![], vec![]);
        let mail = data.mail.unwrap();
        let details = data.mailDetailsBlob.unwrap();
        assert_eq!(mail._ownerKeyVersion, None);
        assert_eq!(details._ownerKeyVersion, None);
        assert_eq!(mail._ownerEncSessionKey, Some(vec![7; 16]));
        assert_eq!(details._ownerEncSessionKey, mail._ownerEncSessionKey);
    }

}
