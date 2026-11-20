#[cfg(test)]
mod velin_attachment_tests {
    use super::*;
    use crate::crypto_entity_client::MockCryptoEntityClient;
    use crate::entities::generated::sys::{BlobReferenceTokenWrapper, GroupMembership, User};
    use crate::entities::generated::tutanota::*;
    use crate::services::generated::tutanota::DraftService;
    use crate::services::service_executor::MockResolvingServiceExecutor;
    use crate::user_facade::{MockUserFacade, VelinEncryptionScheme};
    use crate::util::test_utils::create_test_entity;
    use crypto_primitives::{key::GenericAesKey, versioned::Versioned};

    fn id(value: &str) -> IdTupleGenerated { IdTupleGenerated::new("list".to_owned().into(), value.to_owned().into()) }
    fn owner() -> crypto_primitives::versioned::VersionedAesKey { Versioned::new(GenericAesKey::from_bytes(&[1; 32]).unwrap(), 7) }
    fn file(name: &str, mime: &str, size: usize) -> VelinNewFile { VelinNewFile { name: name.into(), mime_type: mime.into(), data: vec![0; size] } }
    fn input() -> VelinDraftInput {
        VelinDraftInput { id: None, sender: VelinSender { address: "sender@example.invalid".into(), name: "Sender".into() },
            to: vec![VelinSender { address: "recipient@example.invalid".into(), name: String::new() }], cc: vec![], bcc: vec![],
            subject: "FWD: Files".into(), html: "<div>Files</div>".into(), confidential: true, attachments: vec![],
            previous: Some(id("previous")), conversation_type: 2, forwarded: vec![id("original")], new_files: vec![] }
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

    #[test]
    fn velin_new_attachment_matches_upstream_shape() {
        let tokens = vec![BlobReferenceTokenWrapper { _id: None, blobReferenceToken: "token".into() }];
        let attachment = velin_new_attachment(&owner(), vec![9; 80], &file("a.pdf", "application/pdf", 1), tokens.clone()).unwrap();
        assert!(attachment.ownerEncFileSessionKey.is_none() && attachment.ownerKeyVersion.is_none() && attachment.existingFile.is_none());
        let new = attachment.newFile.unwrap();
        assert!(new.encFileName.is_none() && new.encMimeType.is_none() && new.encCid.is_none());
        assert_eq!(new.referenceTokens, tokens);
        let transfer = new.file.unwrap();
        assert_eq!((transfer._ownerEncSessionKey, transfer._ownerKeyVersion, transfer._kdfNonce), (Some(vec![9; 80]), Some(7), None));
        assert_eq!((transfer.name.as_str(), transfer.mimeType.as_deref(), transfer.cid), ("a.pdf", Some("application/pdf"), None));
    }

    #[test]
    fn velin_mime_types_follow_the_upstream_pattern() {
        for valid in ["application/pdf", "image/svg+xml", "application/vnd.ms-excel", "text/x_y"] { assert!(velin_valid_mime_type(valid), "{valid}") }
        for invalid in ["", "pdf", "/pdf", "image/", "text/plain; charset=utf-8", "a/b/c", "text/pl ain", "tëxt/plain"] { assert!(!velin_valid_mime_type(invalid), "{invalid}") }
    }

    #[tokio::test]
    async fn velin_forward_copies_file_keys_and_returns_added_files() {
        let mut client = MockCryptoEntityClient::default();
        sender(&mut client);
        client.expect_load::<Mail, IdTupleGenerated>().returning(|target| Ok(Mail { _id: Some(target.clone()), conversationEntry: id("entry"), ..create_test_entity() }));
        client.expect_load::<ConversationEntry, IdTupleGenerated>().returning(|target| Ok(ConversationEntry {
            _id: Some(target.clone()), messageId: "<m@example.invalid>".into(), ..create_test_entity() }));
        client.expect_velin_draft_owner_key().returning(|_, _| Ok(owner()));
        client.expect_velin_file().withf(|mail, file| mail == &id("previous") && file == &id("original"))
            .times(1).returning(|_, file| Ok(TutanotaFile { _id: Some(file), ..create_test_entity() }));
        client.expect_velin_file_key().times(1).return_once(|_| Ok(GenericAesKey::from_bytes(&[42; 32]).unwrap()));
        let input = input();
        let readback = Mail { _id: Some(id("draft")), mailDetailsDraft: Some(id("details")), subject: input.subject.clone(), confidential: true,
            sender: MailAddress { name: "Sender".into(), address: "sender@example.invalid".into(), ..create_test_entity() },
            attachments: vec![id("copy")], ..create_test_entity() };
        client.expect_velin_draft_mail().returning(move |_| Ok(readback.clone()));
        let html = input.html.clone();
        client.expect_velin_draft_details().returning(move |_| Ok(MailDetails {
            body: Body { text: Some(String::new()), compressedText: Some(html.clone()), ..create_test_entity() },
            recipients: Recipients { toRecipients: vec![MailAddress { address: "recipient@example.invalid".into(), name: String::new(), ..create_test_entity() }],
                ccRecipients: vec![], bccRecipients: vec![], ..create_test_entity() }, replyTos: vec![], ..create_test_entity() }));
        client.expect_velin_draft_file().withf(|target| target == &id("copy")).times(1)
            .returning(|target| Ok(TutanotaFile { _id: Some(target.clone()), name: "a.pdf".into(), ..create_test_entity() }));
        let mut services = MockResolvingServiceExecutor::default();
        services.expect_post::<DraftService>().times(1).withf(|data, _| {
            let [added] = data.draftData.addedAttachments.as_slice() else { return false };
            data.conversationType == 2 && added.existingFile == Some(id("original")) && added.newFile.is_none() && added.ownerKeyVersion == Some(7) &&
                owner().object.decrypt_aes_key(added.ownerEncFileSessionKey.as_ref().unwrap()).unwrap().as_bytes() == [42; 32]
        }).return_once(|_, _| Ok(DraftCreateReturn { _format: 0, draft: id("draft") }));
        let facade = MailFacade::new(Arc::new(client), Arc::new(user()), Arc::new(services));
        let saved = facade.velin_save_draft(input).await.unwrap();
        assert_eq!(saved.id, id("draft"));
        assert_eq!(saved.added.iter().map(|f| f._id.clone().unwrap()).collect::<Vec<_>>(), vec![id("copy")]);
    }

    #[tokio::test]
    async fn velin_invalid_files_fail_before_any_request() {
        let too_big = VELIN_MAX_ATTACHMENT_BYTES / 2 + 1;
        let cases: Vec<Box<dyn Fn(&mut VelinDraftInput)>> = vec![
            Box::new(|i| i.id = Some(id("draft"))),
            Box::new(|i| i.conversation_type = 1),
            Box::new(|i| i.forwarded.push(id("original"))),
            Box::new(|i| i.new_files = vec![file(" ", "text/plain", 1)]),
            Box::new(|i| i.new_files = vec![file("a\nb", "text/plain", 1)]),
            Box::new(|i| i.new_files = vec![file("a.txt", "text/plain; charset=utf-8", 1)]),
            Box::new(move |i| i.new_files = vec![file("a", "text/plain", too_big), file("b", "text/plain", too_big)]),
            Box::new(|i| i.forwarded = (0..101).map(|n| id(&n.to_string())).collect()),
        ];
        for change in cases {
            let mut input = input();
            change(&mut input);
            let facade = MailFacade::new(Arc::new(MockCryptoEntityClient::default()), Arc::new(user()), Arc::new(MockResolvingServiceExecutor::default()));
            assert!(facade.velin_save_draft(input).await.is_err());
        }
    }

    #[tokio::test]
    async fn velin_new_files_need_the_uploader_and_never_post_without_it() {
        let mut input = input();
        input.previous = None;
        input.conversation_type = 0;
        input.forwarded = vec![];
        input.new_files = vec![file("a.txt", "text/plain", 3)];
        let mut client = MockCryptoEntityClient::default();
        sender(&mut client);
        client.expect_velin_draft_owner_key().returning(|_, _| Ok(owner()));
        let mut services = MockResolvingServiceExecutor::default();
        services.expect_post::<DraftService>().never();
        let facade = MailFacade::new(Arc::new(client), Arc::new(user()), Arc::new(services));
        assert!(matches!(facade.velin_save_draft(input).await, Err(ApiCallError::VelinDraftUnsupported)));
    }
}
