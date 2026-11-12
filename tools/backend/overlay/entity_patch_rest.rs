
impl EntityClient {
    // EntityRestClient.ts:690-720 and :819-863. The body is a sys PatchList, so dv names the sys model as upstream does.
    pub async fn velin_patch(&self, type_ref: &TypeRef, id: &crate::IdTupleGenerated,
        patch: crate::entities::generated::sys::PatchList) -> Result<(), ApiCallError> {
        use crate::entities::generated::sys::PatchList;
        use crate::entities::Entity;
        let invalid = || ApiCallError::internal("Invalid entity patch".into());
        let model = self.resolve_client_type_ref(type_ref)?;
        let patch_model = self.resolve_client_type_ref(&PatchList::type_ref())?;
        let parsed = crate::instance_mapper::InstanceMapper::new(self.type_model_provider.clone())
            .serialize_entity(patch).map_err(|_| invalid())?;
        let raw = self.json_serializer.serialize(&PatchList::type_ref(), parsed)?;
        let body = serde_json::to_vec(&raw).map_err(|_| invalid())?;
        let mut headers = self.auth_headers_provider.provide_headers(model.version);
        headers.insert("dv".into(), patch_model.version.to_string());
        headers.insert("Content-Type".into(), "application/json".into());
        let url = format!("{}/rest/{}/{}/{}/{}", self.base_url, type_ref.app, model.name.to_lowercase(), id.list_id, id.element_id);
        let response = self.rest_client.request_binary(url, HttpMethod::PATCH, RestClientOptions {
            body: Some(body), headers, suspension_behavior: Some(SuspensionBehavior::Suspend),
        }).await?;
        self.ensure_latest_server_model(&response).await?;
        if !(200..300).contains(&response.status) {
            return Err(HttpError::from_http_response(response.status, &response.headers)?.into());
        }
        Ok(())
    }
}
