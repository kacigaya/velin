    pub async fn velin_draft_mail(&self, id: &crate::IdTupleGenerated) -> Result<Mail, ApiCallError> {
        let model = self.entity_client.resolve_server_type_ref(&Mail::type_ref())?;
        let raw = self.entity_client.load(&Mail::type_ref(), id).await?;
        let resolved = self.crypto_facade.resolve_session_key(&raw, &model).await
            .map_err(|_| ApiCallError::internal("Draft session key resolution failed".into()))?
            .ok_or_else(|| ApiCallError::internal("Missing draft session key".into()))?;
        let decrypted = crate::velin_session_transfer::decrypt(&self.instance_mapper.type_model_provider, &model, &raw,
            &resolved.session_key, &crypto_primitives::randomizer_facade::RandomizerFacade::from_core(rand_core::OsRng))?;
        let mail: Mail = self.instance_mapper.parse_entity(decrypted).map_err(|_| ApiCallError::internal("Invalid draft".into()))?;
        if mail._id.as_ref() != Some(id) || mail.mailDetailsDraft.is_none() || !mail._errors.is_empty() || !mail.sender._errors.is_empty() {
            return Err(ApiCallError::internal("Invalid draft".into()));
        }
        Ok(mail)
    }

    pub async fn velin_draft_details(&self, mail: &Mail) -> Result<crate::entities::generated::tutanota::MailDetails, ApiCallError> {
        use crate::entities::generated::tutanota::MailDetailsDraft;
        let id = mail.mailDetailsDraft.as_ref().ok_or_else(|| ApiCallError::internal("Missing draft details".into()))?;
        let model = self.entity_client.resolve_server_type_ref(&MailDetailsDraft::type_ref())?;
        let mut raw = self.entity_client.load(&MailDetailsDraft::type_ref(), id).await?;
        raw.insert(model.get_attribute_id_by_attribute_name("_ownerEncSessionKey")?,
            ElementValue::Bytes(mail._ownerEncSessionKey.clone().ok_or_else(|| ApiCallError::internal("Missing draft session key".into()))?));
        raw.insert(model.get_attribute_id_by_attribute_name("_ownerKeyVersion")?, ElementValue::Number(mail._ownerKeyVersion.unwrap_or(0)));
        let resolved = self.crypto_facade.resolve_session_key(&raw, &model).await
            .map_err(|_| ApiCallError::internal("Draft session key resolution failed".into()))?
            .ok_or_else(|| ApiCallError::internal("Missing draft session key".into()))?;
        let decrypted = crate::velin_session_transfer::decrypt(&self.instance_mapper.type_model_provider, &model, &raw,
            &resolved.session_key, &crypto_primitives::randomizer_facade::RandomizerFacade::from_core(rand_core::OsRng))?;
        let details: MailDetailsDraft = self.instance_mapper.parse_entity(decrypted).map_err(|_| ApiCallError::internal("Invalid draft details".into()))?;
        if details._id.as_ref() != Some(id) || !details._errors.is_empty() ||
            !details.details.body._errors.is_empty() || details.details.replyTos.iter().any(|a| !a._errors.is_empty()) ||
            details.details.recipients.toRecipients.iter().chain(&details.details.recipients.ccRecipients).chain(&details.details.recipients.bccRecipients).any(|a| !a._errors.is_empty()) {
            return Err(ApiCallError::internal("Invalid draft details".into()));
        }
        Ok(details.details)
    }

    pub async fn velin_draft_owner_key(&self, group: &GeneratedId, version: Option<u64>) -> Result<crypto_primitives::versioned::VersionedAesKey, ApiCallError> {
        match version {
            Some(version) => Ok(crypto_primitives::versioned::Versioned::new(self.key_loader_facade.load_sym_group_key(group, version, None).await
                .map_err(|_| ApiCallError::internal("Draft owner key load failed".into()))?, version)),
            None => self.key_loader_facade.get_current_sym_group_key(group).await.map_err(|_| ApiCallError::internal("Draft owner key load failed".into())),
        }
    }

    pub async fn velin_draft_file(&self, id: &crate::IdTupleGenerated) -> Result<crate::entities::generated::tutanota::TutanotaFile, ApiCallError> {
        let model = self.entity_client.resolve_server_type_ref(&crate::entities::generated::tutanota::TutanotaFile::type_ref())?;
        let raw = self.entity_client.load(&crate::entities::generated::tutanota::TutanotaFile::type_ref(), id).await?;
        let resolved = self.crypto_facade.resolve_session_key(&raw, &model).await
            .map_err(|_| ApiCallError::internal("Draft session key resolution failed".into()))?
            .ok_or_else(|| ApiCallError::internal("Missing draft session key".into()))?;
        let decrypted = crate::velin_session_transfer::decrypt(&self.instance_mapper.type_model_provider, &model, &raw,
            &resolved.session_key, &crypto_primitives::randomizer_facade::RandomizerFacade::from_core(rand_core::OsRng))?;
        let mail: crate::entities::generated::tutanota::TutanotaFile = self.instance_mapper.parse_entity(decrypted).map_err(|_| ApiCallError::internal("Invalid draft".into()))?;
        Ok(mail)
    }
