    // MailFacade.ts:1587-1631: prefer the file's own key; otherwise resolve the mail bucket.
    pub async fn velin_file(&self, mail_id: crate::IdTupleGenerated, id: crate::IdTupleGenerated)
        -> Result<crate::entities::generated::tutanota::TutanotaFile, ApiCallError> {
        use crate::entities::generated::tutanota::TutanotaFile;
        let mail: Mail = self.load(&mail_id).await?;
        if !mail._errors.is_empty() || !mail.attachments.contains(&id) {
            return Err(ApiCallError::internal("Invalid attachment parent".into()));
        }
        let model = self.entity_client.resolve_server_type_ref(&TutanotaFile::type_ref())?;
        let mut raw = self.entity_client.load(&TutanotaFile::type_ref(), &id).await?;
        let owner_key_id = model.get_attribute_id_by_attribute_name("_ownerEncSessionKey")?;
        if !matches!(raw.get(&owner_key_id), Some(ElementValue::Bytes(_))) {
            if mail.bucketKey.is_none() { return Err(ApiCallError::internal("Missing attachment key".into())); }
            let mail_model = self.entity_client.resolve_server_type_ref(&Mail::type_ref())?;
            let mut parent = self.instance_mapper.serialize_entity(mail)
                .map_err(|_| ApiCallError::internal("Invalid attachment parent".into()))?;
            // Bucket resolution selects the matching instance key and wraps it with the owner key.
            parent.insert(mail_model.get_attribute_id_by_attribute_name("_id")?,
                ElementValue::IdTupleGeneratedElementId(id.clone()));
            let key = self.crypto_facade.resolve_session_key(&parent, &mail_model).await.map_err(|_| ApiCallError::internal("Attachment key resolution failed".into()))?
                .ok_or_else(|| ApiCallError::internal("Missing attachment key".into()))?;
            raw.insert(owner_key_id, ElementValue::Bytes(key.owner_enc_session_key));
            raw.insert(model.get_attribute_id_by_attribute_name("_ownerKeyVersion")?, ElementValue::Number(key.owner_key_version as i64));
        }
        let decrypted = self.process_encrypted_entity(&model, raw).await?;
        let file = self.instance_mapper.parse_entity::<TutanotaFile>(decrypted)
            .map_err(|_| ApiCallError::internal("Invalid attachment".into()))?;
        if file._id.as_ref() != Some(&id) || !file._errors.is_empty() || file.size < 0 {
            return Err(ApiCallError::internal("Invalid attachment".into()));
        }
        Ok(file)
    }

    pub async fn velin_file_content(&self, file: crate::entities::generated::tutanota::TutanotaFile,
        access: crate::entities::generated::storage::BlobServerAccessInfo) -> Result<Vec<u8>, ApiCallError> {
        const LIMIT: usize = 20 * 1024 * 1024;
        if file.size < 0 || file.size as u64 > LIMIT as u64 || file.blobs.len() > 100 {
            return Err(ApiCallError::internal("Attachment exceeds limit".into()));
        }
        let mut encrypted_size = 0usize;
        for blob in &file.blobs {
            if blob.size < 0 { return Err(ApiCallError::internal("Invalid blob size".into())); }
            encrypted_size = encrypted_size.checked_add(blob.size as usize)
                .filter(|n| *n <= LIMIT + 100 * 128).ok_or_else(|| ApiCallError::internal("Attachment exceeds limit".into()))?;
        }
        let model = self.entity_client.resolve_server_type_ref(&crate::entities::generated::tutanota::TutanotaFile::type_ref())?;
        let raw = self.instance_mapper.serialize_entity(file.clone()).map_err(|_| ApiCallError::internal("Invalid attachment".into()))?;
        let key = self.crypto_facade.resolve_session_key(&raw, &model).await.map_err(|_| ApiCallError::internal("Attachment key resolution failed".into()))?
            .ok_or_else(|| ApiCallError::internal("Missing attachment key".into()))?;
        let chunks = self.entity_client.velin_file_blobs(file.blobs.clone(), access).await?;
        let mut output = Vec::new();
        for chunk in chunks {
            let plain = key.session_key.decrypt_data(&chunk).map_err(|_| ApiCallError::internal("Attachment decryption failed".into()))?;
            if plain.len() > LIMIT - output.len() { return Err(ApiCallError::internal("Attachment exceeds limit".into())); }
            output.extend_from_slice(&plain);
        }
        // File.size can be the encrypted size: files of external mail carry their blob size. Upstream does not
        // compare at all. Plaintext is never larger than either size, so keep only that bound.
        if output.len() as i64 > file.size { return Err(ApiCallError::internal("Invalid attachment size".into())); }
        Ok(output)
    }
