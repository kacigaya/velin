impl EntityClient {
    // EntityRestClient.ts:326-373; BlobAccessTokenFacade.ts:200-215,255-266.
    pub async fn velin_blob(&self, type_ref: &TypeRef, id: &crate::IdTupleGenerated,
        access: crate::entities::generated::storage::BlobServerAccessInfo,
    ) -> Result<ParsedEntity, ApiCallError> {
        let model = self.resolve_client_type_ref(type_ref)?;
        let mut params = self.auth_headers_provider.provide_headers(model.version);
        params.insert("ids".into(), id.element_id.to_string());
        params.insert("blobAccessToken".into(), access.blobAccessToken);
        let query = crate::bindings::rest_client::encode_query_params(params);
        let mut last = ApiCallError::internal("No blob server".into());
        for server in access.servers {
            // Credentials may only go to TLS blob endpoints. The Kotlin transport also rejects redirects.
            if !server.url.starts_with("https://") {
                return Err(ApiCallError::internal("Invalid blob server scheme".into()));
            }
            let url = format!("{}/rest/{}/{}/{}{}", server.url.trim_end_matches('/'),
                type_ref.app, model.name, id.list_id, query);
            let response = self.rest_client.velin_blob_binary(url, HttpMethod::GET, RestClientOptions {
                body: None, headers: Default::default(), suspension_behavior: Some(SuspensionBehavior::Suspend),
            }).await?;
            if !(200..300).contains(&response.status) {
                last = HttpError::from_http_response(response.status, &response.headers)?.into();
                continue;
            }
            self.ensure_latest_server_model(&response).await?;
            let bytes = response.body.ok_or_else(|| ApiCallError::internal("Missing blob body".into()))?;
            let mut entities: Vec<RawEntity> = serde_json::from_slice(&bytes)
                .map_err(|_| ApiCallError::internal("Invalid blob response".into()))?;
            if entities.len() != 1 { return Err(ApiCallError::internal("Missing or duplicate mail details".into())); }
            return self.json_serializer.parse(type_ref, entities.remove(0)).map_err(Into::into);
        }
        Err(last)
    }
}
