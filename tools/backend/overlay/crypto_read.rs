    // MailFacade.ts:1318-1327 supplies the parent's owner-encrypted session key and version.
    pub async fn velin_details(&self, mail: Mail,
        access: Option<crate::entities::generated::storage::BlobServerAccessInfo>,
    ) -> Result<crate::entities::generated::tutanota::MailDetails, ApiCallError> {
        use crate::entities::generated::tutanota::{MailDetailsBlob, MailDetailsDraft};
        let (type_ref, id) = if let Some(id) = &mail.mailDetailsDraft {
            (MailDetailsDraft::type_ref(), id)
        } else {
            (MailDetailsBlob::type_ref(), mail.mailDetails.as_ref()
                .ok_or_else(|| ApiCallError::internal("Missing mail details reference".into()))?)
        };
        let mut raw = if let Some(access) = access {
            self.entity_client.velin_blob(&type_ref, id, access).await?
        } else {
            self.entity_client.load(&type_ref, id).await?
        };
        let model = self.entity_client.resolve_server_type_ref(&type_ref)?;
        if let Some(key) = mail._ownerEncSessionKey {
            raw.insert(model.get_attribute_id_by_attribute_name("_ownerEncSessionKey")?, ElementValue::Bytes(key));
            raw.insert(model.get_attribute_id_by_attribute_name("_ownerKeyVersion")?, ElementValue::Number(mail._ownerKeyVersion.unwrap_or(0)));
        }
        let decrypted = self.process_encrypted_entity(&model, raw).await?;
        if mail.mailDetailsDraft.is_some() {
            let entity = self.instance_mapper.parse_entity::<MailDetailsDraft>(decrypted)
                .map_err(|_| ApiCallError::internal("Invalid draft details".into()))?;
            if entity._id.as_ref() != Some(id) || !entity._errors.is_empty() {
                return Err(ApiCallError::internal("Invalid draft decryption".into()));
            }
            Ok(entity.details)
        } else {
            let entity = self.instance_mapper.parse_entity::<MailDetailsBlob>(decrypted)
                .map_err(|_| ApiCallError::internal("Invalid received details".into()))?;
            if entity._id.as_ref() != Some(id) || !entity._errors.is_empty() {
                return Err(ApiCallError::internal("Invalid received decryption".into()));
            }
            Ok(entity.details)
        }
    }

