
// DraftUpdateReturn keeps a legacy encrypted marker but has no ciphertext.
// Accept only this ID-only transfer model. Model drift must require keys again.
fn velin_plain_draft_update_return(model: &TypeModel) -> bool {
    model.app == crate::metamodel::AppName::Tutanota && model.id == 523u64.into() &&
        model.element_type == crate::metamodel::ElementType::DataTransfer &&
        model.values.values().all(|v| !v.encrypted) &&
        model.associations.values().all(|a| a.association_type != crate::metamodel::AssociationType::Aggregation)
}

#[cfg(test)]
mod velin_draft_response_tests {
    use super::*;
    use crate::bindings::{rest_client::MockRestClient, file_client::MockFileClient};
    use crate::entities::entity_facade::MockEntityFacade;
    use crate::entities::generated::tutanota::DraftUpdateReturn;

    #[tokio::test]
    async fn velin_draft_update_response_has_no_session_key() {
        let models = Arc::new(TypeModelProvider::new_test(Arc::new(MockRestClient::default()), Arc::new(MockFileClient::default()), "https://example.invalid".into()));
        let model = models.resolve_client_type_ref(&DraftUpdateReturn::type_ref()).unwrap();
        assert!(model.marked_encrypted());
        let executor = ServiceExecutor::new(Arc::new(HeadersProvider::new(None)), None,
            Arc::new(MockEntityFacade::default()), Arc::new(InstanceMapper::new(models.clone())),
            Arc::new(JsonSerializer::new(models.clone())), Arc::new(MockRestClient::default()), models, "https://example.invalid".into());
        let vectors: serde_json::Value = serde_json::from_str(include_str!("../velin_drafts.json")).unwrap();
        let wire = vectors["draftUpdateReturnWire"].as_str().unwrap().as_bytes().to_vec();
        let response = executor.handle_response::<DraftUpdateReturn>(Some(wire)).await.unwrap();
        assert_eq!(response.attachments, vec![crate::IdTupleGenerated::new("test-file-list".to_owned().into(), "retained-file".to_owned().into())]);
    }

    #[test]
    fn velin_draft_response_model_drift_does_not_bypass_crypto() {
        use crate::metamodel::{AppName, TypeId};
        let mut model = crate::type_model_provider::CLIENT_TYPE_MODEL.apps[&AppName::Tutanota].types[&TypeId::from(523)].as_ref().clone();
        assert!(velin_plain_draft_update_return(&model));
        model.values.values_mut().next().unwrap().encrypted = true;
        assert!(!velin_plain_draft_update_return(&model));
        model.values.values_mut().next().unwrap().encrypted = false;
        model.associations.values_mut().next().unwrap().association_type = crate::metamodel::AssociationType::Aggregation;
        assert!(!velin_plain_draft_update_return(&model));
    }
}
