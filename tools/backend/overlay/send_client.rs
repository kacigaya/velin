impl CryptoEntityClient {
    pub async fn velin_send_mail_key(&self, mail: &Mail) -> Result<GenericAesKey, ApiCallError> {
        let raw = self.instance_mapper.serialize_entity(mail.clone()).map_err(|_| ApiCallError::VelinRecipientInvalidKey)?;
        let model = self.entity_client.resolve_server_type_ref(&Mail::type_ref())?;
        Ok(self.crypto_facade.resolve_session_key(&raw, &model).await
            .map_err(|_| ApiCallError::VelinRecipientInvalidKey)?
            .ok_or(ApiCallError::VelinRecipientInvalidKey)?.session_key)
    }

    pub async fn velin_send_recipient_key(&self, key: crate::entities::generated::sys::PublicKeyGetOut,
        bucket: GenericAesKey, sender: &GeneratedId, address: String,
    ) -> Result<crate::entities::generated::tutanota::InternalRecipientKeyData, ApiCallError> {
        let public_key = crate::crypto::public_key_provider::PublicKeyProvider::convert_to_versioned_public_keys(key);
        let encrypted = self.asymmetric_crypto_facade.asym_encrypt_sym_key(bucket, public_key, sender).await
            .map_err(|_| ApiCallError::VelinRecipientInvalidKey)?;
        Ok(crate::entities::generated::tutanota::InternalRecipientKeyData {
            _id: None, mailAddress: address, pubEncBucketKey: encrypted.pub_enc_sym_key_bytes,
            recipientKeyVersion: i64::try_from(encrypted.recipient_key_version).map_err(|_| ApiCallError::VelinRecipientInvalidKey)?,
            senderKeyVersion: encrypted.sender_key_version.map(i64::try_from).transpose().map_err(|_| ApiCallError::VelinRecipientInvalidKey)?,
            protocolVersion: encrypted.crypto_protocol_version as i64,
        })
    }

    pub async fn velin_send_plaintext_setting(&self, group: &GeneratedId) -> Result<bool, ApiCallError> {
        use crate::entities::generated::{sys::RootInstance, tutanota::TutanotaProperties};
        let model = self.entity_client.resolve_server_type_ref(&TutanotaProperties::type_ref())?;
        let id = crate::id::id_tuple::IdTupleCustom { list_id: group.clone(), element_id: model.root_id.clone() };
        let root: RootInstance = self.load(&id).await?;
        if root._id.as_ref() != Some(&id) { return Err(ApiCallError::VelinRecipientInvalidKey) }
        let properties: TutanotaProperties = self.load(&root.reference).await?;
        if properties._id.as_ref() != Some(&root.reference) || !properties._errors.is_empty() {
            return Err(ApiCallError::VelinRecipientInvalidKey);
        }
        Ok(properties.sendPlaintextOnly)
    }
}
