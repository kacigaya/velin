    /// EntityRestClient.ts:690-720 for one changed value. With AEAD the instance must carry its KDF nonce:
    /// MailFacade asks UpdateKdfNonceService for one first, as ensureKdfNonce does.
    pub async fn velin_patch_value<Instance: Entity + Serialize>(&self, instance: Instance, attribute_name: &str, aead: bool,
        random: &crypto_primitives::randomizer_facade::RandomizerFacade) -> Result<(), ApiCallError> {
        use crate::element_value::ElementValue;
        use crate::velin_entity_patch::{encrypt_value, patch_list, VelinValueKey};
        let invalid = || ApiCallError::internal("Invalid entity patch".into());
        let type_ref = Instance::type_ref();
        let model = self.entity_client.resolve_client_type_ref(&type_ref)?;
        let parsed = self.instance_mapper.serialize_entity(instance).map_err(|_| invalid())?;
        let field = |name: &str| model.get_attribute_id_by_attribute_name(name).map(|id| parsed.get(&id).cloned());
        let Some(ElementValue::IdTupleGeneratedElementId(id)) = field("_id")? else { return Err(invalid()) };
        let key = if aead {
            let (Some(ElementValue::IdGeneratedId(group)), Some(ElementValue::Bytes(kdf_nonce))) = (field("_ownerGroup")?, field("_kdfNonce")?)
                else { return Err(invalid()) };
            let group_key = self.key_loader_facade.get_current_sym_group_key(&group).await
                .map_err(|_| ApiCallError::internal("Group key load failed".into()))?;
            VelinValueKey::Instance { group_key, kdf_nonce }
        } else {
            let resolved = self.crypto_facade.resolve_session_key(&parsed, model).await
                .map_err(|_| ApiCallError::internal("Session key resolution failed".into()))?
                .ok_or_else(|| ApiCallError::internal("Session key resolution failed".into()))?;
            VelinValueKey::Session(resolved.session_key)
        };
        let attribute = model.get_attribute_id_by_attribute_name(attribute_name)?;
        let ciphertext = encrypt_value(model, &attribute, parsed.get(&attribute).ok_or_else(invalid)?, &key, random)?;
        self.entity_client.velin_patch(&type_ref, &id, patch_list(&attribute, &ciphertext, random)).await
    }

    /// Values another client patched with the instance key. The rest of the instance keeps its session key.
    async fn velin_instance_key_values(&self, type_model: &TypeModel, entity: &crate::element_value::ParsedEntity)
        -> Result<Vec<(String, crate::element_value::ElementValue)>, ApiCallError> {
        let (found, context) = crate::velin_entity_patch::instance_key_values(type_model, entity);
        // Without an owner group or nonce the session-key pass fails the instance, as in the release.
        let Some((group, kdf_nonce)) = context else { return Ok(vec![]) };
        let mut values = Vec::with_capacity(found.len());
        for (attribute, version) in found {
            let key = self.key_loader_facade.load_sym_group_key(&group, version, None).await
                .map_err(|_| ApiCallError::internal("Group key load failed".into()))?;
            let Some(crate::element_value::ElementValue::Bytes(ciphertext)) = entity.get(&attribute) else { continue };
            // An altered value stays in place, so the session-key pass fails the instance closed.
            if let Ok(value) = crate::velin_entity_patch::decrypt_instance_value(type_model, &attribute, ciphertext,
                &crypto_primitives::versioned::Versioned::new(key, version), &kdf_nonce) {
                values.push((attribute, value));
            }
        }
        Ok(values)
    }

