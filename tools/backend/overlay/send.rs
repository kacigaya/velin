fn velin_send_data(id: IdTupleGenerated, wrapped: Vec<u8>, internal: Vec<crate::entities::generated::tutanota::InternalRecipientKeyData>,
    plaintext: bool, status: Option<Vec<u8>>,
) -> crate::entities::generated::tutanota::SendDraftData {
    use crate::entities::generated::tutanota::{SendDraftData, SendDraftParameters};
    let parameters = SendDraftParameters { _id: None, language: "en".into(), mail: id.clone(), mailSessionKey: None,
        bucketEncMailSessionKey: Some(wrapped.clone()), senderNameUnencrypted: None, plaintext, calendarMethod: false,
        sessionEncEncryptionAuthStatus: status.clone(), internalRecipientKeyData: internal.clone(),
        secureExternalRecipientKeyData: vec![], symEncInternalRecipientKeyData: vec![], attachmentKeyData: vec![] };
    SendDraftData { _format: 0, language: "en".into(), mail: id, mailSessionKey: None,
        bucketEncMailSessionKey: Some(wrapped), senderNameUnencrypted: None, plaintext, calendarMethod: false,
        sessionEncEncryptionAuthStatus: status, sendAt: None, allowUndo: false, internalRecipientKeyData: internal,
        secureExternalRecipientKeyData: vec![], symEncInternalRecipientKeyData: vec![], attachmentKeyData: vec![], parameters: Some(parameters) }
}

#[uniffi::export]
impl MailFacade {
    pub async fn velin_send_draft(&self, id: IdTupleGenerated) -> Result<(), ApiCallError> {
        use crate::entities::generated::tutanota::ConversationEntry;
        use crate::services::{generated::tutanota::SendDraftService, ExtraServiceParams};
        use crypto_primitives::{aes::{Aes256Key, InitializationVector}, randomizer_facade::RandomizerFacade};
        AssertUnwindSafe(async {
            // Validate the authenticated persisted draft. Never trust composer type flags or cached public keys.
            let mail = self.crypto_entity_client.velin_draft_mail(&id).await?;
            if mail.sendAt.is_some() { return Err(ApiCallError::VelinSendUnsupported { reason: 1 }) }
            if !mail.confidential { return Err(ApiCallError::VelinSendUnsupported { reason: 2 }) }
            if !mail.attachments.is_empty() { return Err(ApiCallError::VelinSendUnsupported { reason: 3 }) }
            let conversation: ConversationEntry = self.crypto_entity_client.load(&mail.conversationEntry).await?;
            if conversation._id.as_ref() != Some(&mail.conversationEntry) { return Err(ApiCallError::VelinRecipientInvalidKey) }
            // Replies and forwards thread on the server. A new mail has no previous entry.
            let new_mail = conversation.conversationType == 0 && conversation.previous.is_none();
            if !(new_mail || matches!(conversation.conversationType, 1 | 2)) || mail.replyType != 0 {
                return Err(ApiCallError::VelinSendUnsupported { reason: 4 });
            }
            if mail.method != 0 { return Err(ApiCallError::VelinSendUnsupported { reason: 7 }) }
            let group = self.velin_draft_sender_group(&mail.sender.address).await?;
            if mail._ownerGroup.as_ref() != Some(&group) { return Err(ApiCallError::VelinRecipientInvalidKey) }
            if self.velin_trust.read().map_err(|_| ApiCallError::VelinRecipientInvalidKey)?.is_none() {
                return Err(ApiCallError::VelinSendUnsupported { reason: 5 });
            }
            let details = self.crypto_entity_client.velin_draft_details(&mail).await?;
            if !details.replyTos.is_empty() { return Err(ApiCallError::VelinSendUnsupported { reason: 8 }) }
            let recipients: Vec<_> = details.recipients.toRecipients.iter().chain(&details.recipients.ccRecipients)
                .chain(&details.recipients.bccRecipients).collect();
            if recipients.is_empty() || recipients.len() > 1000 { return Err(ApiCallError::VelinSendBlocked { reason: 0, indices: vec![] }) }
            let mut missing = Vec::new();
            let mut mismatched = Vec::new();
            let mut keys = Vec::new();
            for (index, recipient) in recipients.iter().enumerate() {
                let address = recipient.address.trim().to_lowercase();
                if address == crate::tutanota_constants::SYSTEM_GROUP_MAIL_ADDRESS {
                    missing.push(index as u32); continue;
                }
                let (key, state) = self.velin_load_recipient_key(&address).await?;
                if state == 2 { mismatched.push(index as u32); }
                match key {
                    Some(key) => keys.push((address, key)),
                    None if velin_tuta_address(&address) => missing.push(index as u32),
                    None => return Err(ApiCallError::VelinSendUnsupported { reason: 6 }),
                }
            }
            if !missing.is_empty() { return Err(ApiCallError::VelinSendBlocked { reason: 0, indices: missing }) }
            if !mismatched.is_empty() { return Err(ApiCallError::VelinSendBlocked { reason: 1, indices: mismatched }) }
            let user = self.user_facade.get_user();
            let own_mail = user.memberships.iter().find(|m| m.group_type() == GroupType::Mail)
                .ok_or(ApiCallError::VelinRecipientInvalidKey)?;
            let sender = if own_mail.group == group { &user.userGroup.group } else { &group };
            let plaintext = self.crypto_entity_client.velin_send_plaintext_setting(&user.userGroup.group).await?;
            let session_key = self.crypto_entity_client.velin_send_mail_key(&mail).await?;
            let random = RandomizerFacade::from_core(rand_core::OsRng);
            let bucket = crypto_primitives::key::GenericAesKey::from(Aes256Key::generate(&random));
            // Release KeyEncryption always uses CBC+HMAC for a 256-bit bucket key, including AEAD accounts.
            let wrapped = bucket.encrypt_key(&session_key, InitializationVector::generate(&random));
            let mut internal = Vec::new();
            for (address, key) in keys {
                internal.push(self.crypto_entity_client.velin_send_recipient_key(key, bucket.clone(), sender, address).await?);
            }
            let status = if internal.iter().all(|key| key.protocolVersion == crate::tutanota_constants::CryptoProtocolVersion::TutaCrypt as i64) {
                Some(session_key.encrypt_data(b"4", InitializationVector::generate(&random)).map_err(|_| ApiCallError::VelinRecipientInvalidKey)?)
            } else { None };
            let data = velin_send_data(id, wrapped, internal, plaintext, status);
            // A failed response may follow a successful send. Never retry this request automatically.
            self.service_executor.post::<SendDraftService>(data, ExtraServiceParams { velin_send: true, ..Default::default() }).await?;
            Ok(())
        }).catch_unwind().await.unwrap_or_else(|_| Err(ApiCallError::VelinRecipientInvalidKey))
    }
}
