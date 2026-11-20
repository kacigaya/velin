
#[derive(uniffi::Record, Clone)]
pub struct VelinDraftInput {
    pub id: Option<IdTupleGenerated>,
    pub sender: VelinSender,
    pub to: Vec<VelinSender>,
    pub cc: Vec<VelinSender>,
    pub bcc: Vec<VelinSender>,
    pub subject: String,
    pub html: String,
    pub confidential: bool,
    pub attachments: Vec<IdTupleGenerated>,
    /// The replied or forwarded mail. Used only when the draft is created.
    pub previous: Option<IdTupleGenerated>,
    /// ConversationType: 0 new, 1 reply, 2 forward. Used only when the draft is created.
    pub conversation_type: i64,
    pub forwarded: Vec<IdTupleGenerated>,
    pub new_files: Vec<VelinNewFile>,
}

#[derive(uniffi::Record, Clone)]
pub struct VelinNewFile {
    pub name: String,
    pub mime_type: String,
    pub data: Vec<u8>,
}

#[derive(uniffi::Record, Clone)]
pub struct VelinSavedDraft {
    pub id: IdTupleGenerated,
    pub added: Vec<crate::entities::generated::tutanota::TutanotaFile>,
}

const VELIN_MAX_ATTACHMENT_BYTES: usize = 25 * 1024 * 1024;

fn velin_valid_mime_type(value: &str) -> bool {
    let Some((kind, subtype)) = value.split_once('/') else { return false };
    !kind.is_empty() && kind.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') &&
        !subtype.is_empty() && subtype.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '+' | '-'))
}

fn velin_valid_files(input: &VelinDraftInput) -> bool {
    let total = input.new_files.iter().try_fold(0usize, |sum, f| sum.checked_add(f.data.len())).filter(|t| *t <= VELIN_MAX_ATTACHMENT_BYTES);
    total.is_some() && input.attachments.len() + input.forwarded.len() + input.new_files.len() <= 100 &&
        input.new_files.iter().all(|f| !f.name.trim().is_empty() && f.name.chars().count() <= 255 && !f.name.chars().any(char::is_control) && velin_valid_mime_type(&f.mime_type)) &&
        input.forwarded.iter().enumerate().all(|(i, id)| !input.forwarded[..i].contains(id))
}

fn velin_new_attachment(owner_key: &crypto_primitives::versioned::VersionedAesKey, wrapped: Vec<u8>, file: &VelinNewFile,
    tokens: Vec<crate::entities::generated::sys::BlobReferenceTokenWrapper>,
) -> Result<crate::entities::generated::tutanota::DraftAttachment, ApiCallError> {
    use crate::entities::generated::tutanota::*;
    Ok(DraftAttachment { _id: None, ownerEncFileSessionKey: None, ownerKeyVersion: None, existingFile: None,
        newFile: Some(NewDraftAttachment { _id: None, encFileName: None, encMimeType: None, encCid: None, referenceTokens: tokens,
            file: Some(FileTransferAggregatedType { _id: None, _ownerEncSessionKey: Some(wrapped),
                _ownerKeyVersion: Some(owner_key.version.try_into().map_err(|_| ApiCallError::internal("Invalid draft key version".into()))?),
                _kdfNonce: None, name: file.name.clone(), mimeType: Some(file.mime_type.clone()), cid: None, _errors: Default::default() }) }) })
}

#[derive(uniffi::Record, Clone)]
pub struct VelinDraftContent {
    pub mail: Mail,
    pub details: crate::entities::generated::tutanota::MailDetails,
    pub attachments: Vec<crate::entities::generated::tutanota::TutanotaFile>,
}

fn velin_draft_data(input: &VelinDraftInput, wrapped: Vec<u8>, version: Option<i64>, nonce: Option<Vec<u8>>,
    method: i64, reply_tos: Vec<crate::entities::generated::tutanota::EncryptedMailAddress>, removed: Vec<IdTupleGenerated>,
    added: Vec<crate::entities::generated::tutanota::DraftAttachment>,
) -> crate::entities::generated::tutanota::DraftData {
    use crate::entities::generated::tutanota::*;
    let address = |a: &VelinSender| MailAddressTransferAggregatedType {
        _id: None, name: a.name.clone(), address: a.address.clone(), contact: None, _errors: Default::default(),
    };
    DraftData { _id: None, subject: None, bodyText: None, senderMailAddress: None, senderName: None,
        confidential: None, method: None, compressedBodyText: None, toRecipients: vec![], ccRecipients: vec![], bccRecipients: vec![],
        addedAttachments: added, removedAttachments: removed, replyTos: vec![], _errors: Default::default(),
        mail: Some(MailTransferAggregatedType {
            _id: None, _ownerEncSessionKey: Some(wrapped.clone()), _ownerKeyVersion: version, _kdfNonce: nonce.clone(),
            subject: input.subject.clone(), confidential: input.confidential, method, sender: address(&input.sender),
            firstRecipient: input.to.first().or(input.cc.first()).or(input.bcc.first()).map(address), _errors: Default::default(),
        }),
        mailDetailsBlob: Some(MailDetailsBlobTransferAggregatedType {
            _id: None, _ownerEncSessionKey: Some(wrapped), _ownerKeyVersion: version, _kdfNonce: nonce,
            details: MailDetailsTransferAggregatedType {
                _id: None,
                replyTos: reply_tos.into_iter().map(|a| EncryptedMailAddressTransferAggregatedType {
                    _id: None, name: a.name, address: a.address, _errors: Default::default(),
                }).collect(),
                recipients: RecipientsTransferAggregatedType {
                    _id: None, toRecipients: input.to.iter().map(address).collect(), ccRecipients: input.cc.iter().map(address).collect(),
                    bccRecipients: input.bcc.iter().map(address).collect(),
                },
                body: BodyTransferAggregatedType { _id: None, text: Some(String::new()), compressedText: Some(input.html.clone()), _errors: Default::default() },
            },
        }),
    }
}

impl MailFacade {
    async fn velin_draft_sender_group(&self, address: &str) -> Result<GeneratedId, ApiCallError> {
        let user = self.user_facade.get_user();
        for membership in user.memberships.iter().filter(|m| m.group_type() == GroupType::Mail) {
            let group: Group = self.crypto_entity_client.load(&membership.group).await?;
            if group._id.as_ref() != Some(&membership.group) { return Err(ApiCallError::internal("Invalid sender group".into())) }
            let Some(info_id) = velin_sender_info_id(&group, &user, membership) else { continue };
            let info: GroupInfo = self.crypto_entity_client.load(info_id).await?;
            if info._id.as_ref() != Some(info_id) || !info._errors.is_empty() { return Err(ApiCallError::internal("Invalid sender".into())) }
            if get_enabled_mail_addresses_for_group_info(&info).iter().any(|a| a == address) { return Ok(membership.group.clone()) }
        }
        Err(ApiCallError::internal("Draft sender is not enabled".into()))
    }

    /// SendMailModel.ts:483: the server threads a response by the previous message ID.
    /// A deleted mail or entry leaves the ID empty, as upstream does.
    async fn velin_previous_message_id(&self, previous: &IdTupleGenerated) -> Result<Option<String>, ApiCallError> {
        use crate::entities::generated::tutanota::ConversationEntry;
        let not_found = |e: &ApiCallError| matches!(e, ApiCallError::ServerResponseError { source: HttpError::NotFoundError });
        let mail: Mail = match self.crypto_entity_client.load(previous).await {
            Ok(mail) => mail,
            Err(e) if not_found(&e) => return Ok(None),
            Err(e) => return Err(e),
        };
        if mail._id.as_ref() != Some(previous) { return Err(ApiCallError::internal("Invalid previous mail".into())) }
        let entry: ConversationEntry = match self.crypto_entity_client.load(&mail.conversationEntry).await {
            Ok(entry) => entry,
            Err(e) if not_found(&e) => return Ok(None),
            Err(e) => return Err(e),
        };
        if entry._id.as_ref() != Some(&mail.conversationEntry) { return Err(ApiCallError::internal("Invalid previous mail".into())) }
        Ok(Some(entry.messageId))
    }

    async fn velin_draft_matches(&self, id: &IdTupleGenerated, input: &VelinDraftInput) -> Result<Option<Mail>, ApiCallError> {
        let mail = self.crypto_entity_client.velin_draft_mail(id).await?;
        let details = self.crypto_entity_client.velin_draft_details(&mail).await?;
        let addresses_match = |actual: &[crate::entities::generated::tutanota::MailAddress], expected: &[VelinSender]| {
            actual.len() == expected.len() && actual.iter().zip(expected).all(|(a, b)| a.address == b.address && a.name == b.name)
        };
        let added = input.forwarded.len() + input.new_files.len();
        Ok((mail.subject == input.subject && mail.sender.address == input.sender.address && mail.sender.name == input.sender.name &&
            mail.confidential == input.confidential && details.body.compressedText.as_deref().or(details.body.text.as_deref()).unwrap_or("") == input.html &&
            addresses_match(&details.recipients.toRecipients, &input.to) && addresses_match(&details.recipients.ccRecipients, &input.cc) &&
            addresses_match(&details.recipients.bccRecipients, &input.bcc) &&
            mail.attachments.len() == input.attachments.len() + added && input.attachments.iter().all(|a| mail.attachments.contains(a))).then_some(mail))
    }

    async fn velin_new_attachments(&self, group: &GeneratedId, owner_key: &crypto_primitives::versioned::VersionedAesKey, files: &[VelinNewFile],
    ) -> Result<Vec<crate::entities::generated::tutanota::DraftAttachment>, ApiCallError> {
        use crypto_primitives::{aes::Aes256Key, key::GenericAesKey, randomizer_facade::RandomizerFacade};
        if files.is_empty() { return Ok(vec![]) }
        let blob = self.velin_blob.as_ref().ok_or(ApiCallError::VelinDraftUnsupported)?;
        let random = RandomizerFacade::from_core(rand_core::OsRng);
        let mut added = Vec::with_capacity(files.len());
        for file in files {
            let key = GenericAesKey::from(Aes256Key::generate(&random));
            let tokens = blob.encrypt_and_upload_single_legacy(crate::tutanota_constants::ArchiveDataType::Attachments, group, &key, &file.data).await?;
            let wrapped = crate::velin_session_transfer::wrap_session_key(&owner_key.object, &key, &random);
            added.push(velin_new_attachment(owner_key, wrapped, file, tokens)?);
        }
        Ok(added)
    }

    async fn velin_forwarded_attachments(&self, previous: &IdTupleGenerated, owner_key: &crypto_primitives::versioned::VersionedAesKey, files: &[IdTupleGenerated],
    ) -> Result<Vec<crate::entities::generated::tutanota::DraftAttachment>, ApiCallError> {
        use crate::entities::generated::tutanota::DraftAttachment;
        let random = crypto_primitives::randomizer_facade::RandomizerFacade::from_core(rand_core::OsRng);
        let version = owner_key.version.try_into().map_err(|_| ApiCallError::internal("Invalid draft key version".into()))?;
        let mut added = Vec::with_capacity(files.len());
        for id in files {
            let file = self.crypto_entity_client.velin_file(previous.clone(), id.clone()).await?;
            let key = self.crypto_entity_client.velin_file_key(file).await?;
            added.push(DraftAttachment { _id: None, ownerEncFileSessionKey: Some(crate::velin_session_transfer::wrap_session_key(&owner_key.object, &key, &random)),
                ownerKeyVersion: Some(version), existingFile: Some(id.clone()), newFile: None });
        }
        Ok(added)
    }
}

#[uniffi::export]
impl MailFacade {
    pub async fn velin_load_draft(&self, id: IdTupleGenerated) -> Result<VelinDraftContent, ApiCallError> {
        AssertUnwindSafe(async {
            let mail = self.crypto_entity_client.velin_draft_mail(&id).await?;
            let details = self.crypto_entity_client.velin_draft_details(&mail).await?;
            if mail.attachments.len() > 100 { return Err(ApiCallError::VelinDraftUnsupported) }
            let mut attachments = Vec::new();
            for file_id in &mail.attachments {
                let file = self.crypto_entity_client.velin_draft_file(file_id).await?;
                if file._id.as_ref() != Some(file_id) || !file._errors.is_empty() { return Err(ApiCallError::internal("Invalid draft attachment".into())) }
                attachments.push(file);
            }
            Ok(VelinDraftContent { mail, details, attachments })
        }).catch_unwind().await.unwrap_or_else(|_| Err(ApiCallError::internal("Draft load failed".into())))
    }

    pub async fn velin_save_draft(&self, input: VelinDraftInput) -> Result<VelinSavedDraft, ApiCallError> {
        use crate::entities::generated::tutanota::{DraftCreateData, DraftUpdateData};
        use crate::services::generated::tutanota::DraftService;
        use crate::services::ExtraServiceParams;
        use crate::velin_session_transfer::VelinSessionTransferEncryption;
        use crypto_primitives::{aes::Aes256Key, randomizer_facade::RandomizerFacade};
        AssertUnwindSafe(async {
            if input.html.len() > 1024 * 1024 || input.attachments.len() > 100 ||
                std::iter::once(&input.sender).chain(&input.to).chain(&input.cc).chain(&input.bcc)
                    .any(|a| a.address.is_empty() || a.address.chars().any(|c| matches!(c, '\r' | '\n' | '\0'))) || input.attachments.iter().enumerate().any(|(i, id)| input.attachments[..i].contains(id)) ||
                !velin_valid_files(&input) {
                return Err(ApiCallError::internal("Invalid draft input".into()));
            }
            if !matches!(input.conversation_type, 0..=2) || input.previous.is_some() != (input.conversation_type != 0) ||
                (!input.forwarded.is_empty() && (input.id.is_some() || input.conversation_type != 2)) {
                return Err(ApiCallError::internal("Invalid draft conversation".into()));
            }
            let group = self.velin_draft_sender_group(&input.sender.address).await?;
            let scheme = self.velin_service_encryption_scheme().await?;
            let id = if let Some(id) = &input.id {
                let draft = self.crypto_entity_client.velin_draft_mail(id).await?;
                if draft.sendAt.is_some() { return Err(ApiCallError::VelinDraftUnsupported) }
                if draft._ownerGroup.as_ref() != Some(&group) { return Err(ApiCallError::internal("Draft mailbox cannot change".into())) }
                if input.attachments.iter().any(|a| !draft.attachments.contains(a)) { return Err(ApiCallError::VelinDraftUnsupported) }
                let version = draft._ownerKeyVersion.unwrap_or(0);
                let owner_key = self.crypto_entity_client.velin_draft_owner_key(&group, Some(version.try_into().map_err(|_| ApiCallError::internal("Invalid draft key version".into()))?)).await?;
                let details = self.crypto_entity_client.velin_draft_details(&draft).await?;
                let added = self.velin_new_attachments(&group, &owner_key, &input.new_files).await?;
                let data = velin_draft_data(&input, draft._ownerEncSessionKey.ok_or_else(|| ApiCallError::internal("Missing draft session key".into()))?,
                    draft._ownerKeyVersion, draft._kdfNonce, draft.method, details.replyTos,
                    draft.attachments.into_iter().filter(|id| !input.attachments.contains(id)).collect(), added);
                self.service_executor.put::<DraftService>(DraftUpdateData { _format: 0, draft: id.clone(), draftData: data, _errors: Default::default() },
                    ExtraServiceParams { velin_session_transfer: Some(VelinSessionTransferEncryption { owner_key, scheme }), ..Default::default() }).await?;
                id.clone()
            } else {
                if !input.attachments.is_empty() { return Err(ApiCallError::VelinDraftUnsupported) }
                let previous_message_id = match &input.previous {
                    Some(previous) => self.velin_previous_message_id(previous).await?,
                    None => None,
                };
                let owner_key = self.crypto_entity_client.velin_draft_owner_key(&group, None).await?;
                let mut added = match &input.previous {
                    Some(previous) if !input.forwarded.is_empty() => self.velin_forwarded_attachments(previous, &owner_key, &input.forwarded).await?,
                    _ => vec![],
                };
                added.extend(self.velin_new_attachments(&group, &owner_key, &input.new_files).await?);
                let random = RandomizerFacade::from_core(rand_core::OsRng);
                let session_key = Aes256Key::generate(&random).into();
                let wrapped = crate::velin_session_transfer::wrap_session_key(&owner_key.object, &session_key, &random);
                let version = owner_key.version.try_into().map_err(|_| ApiCallError::internal("Invalid draft key version".into()))?;
                let data = velin_draft_data(&input, wrapped, Some(version), None, 0, vec![], vec![], added);
                self.service_executor.post::<DraftService>(DraftCreateData { _format: 0, previousMessageId: previous_message_id, conversationType: input.conversation_type,
                    ownerEncSessionKey: None, ownerKeyVersion: None, draftData: data, _errors: Default::default() },
                    ExtraServiceParams { velin_session_transfer: Some(VelinSessionTransferEncryption { owner_key, scheme }), ..Default::default() }).await?.draft
            };
            // The SDK has no event replay yet. Confirm visibility with bounded reads.
            // Never retry POST/PUT: a lost response can already mean a saved draft.
            for attempt in 0..20 {
                match self.velin_draft_matches(&id, &input).await {
                    Ok(Some(mail)) => {
                        let mut added = Vec::new();
                        for file_id in mail.attachments.iter().filter(|a| !input.attachments.contains(a)) {
                            let file = self.crypto_entity_client.velin_draft_file(file_id).await?;
                            if file._id.as_ref() != Some(file_id) || !file._errors.is_empty() { return Err(ApiCallError::internal("Invalid draft attachment".into())) }
                            added.push(file);
                        }
                        return Ok(VelinSavedDraft { id, added });
                    },
                    Ok(None) | Err(ApiCallError::ServerResponseError { source: HttpError::NotFoundError }) => {},
                    Err(error) => return Err(error),
                }
                if attempt < 19 { tokio::time::sleep(std::time::Duration::from_millis(250)).await; }
            }
            Err(ApiCallError::internal("Draft update not visible".into()))
        }).catch_unwind().await.unwrap_or_else(|_| Err(ApiCallError::internal("Draft save failed".into())))
    }
}
