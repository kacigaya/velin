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

impl EntityClient {
    pub async fn velin_file_blobs(&self, blobs: Vec<crate::entities::generated::sys::Blob>,
        access: crate::entities::generated::storage::BlobServerAccessInfo) -> Result<Vec<Vec<u8>>, ApiCallError> {
        use crate::entities::generated::storage::BlobGetIn;
        if blobs.is_empty() { return Ok(vec![]); }
        // BlobFacade.ts:1027-1040; service_executor.rs:277 uses _body for GET.
        let input = velin_blob_get_in(&blobs)?;
        let mapper = crate::instance_mapper::InstanceMapper::new(self.type_model_provider.clone());
        let parsed = mapper.serialize_entity(input).map_err(|_| ApiCallError::internal("Invalid blob request".into()))?;
        let raw = self.json_serializer.serialize(&BlobGetIn::type_ref(), parsed)?;
        let mut params = self.auth_headers_provider.provide_headers(15);
        params.insert("blobAccessToken".into(), access.blobAccessToken);
        params.insert("_body".into(), serde_json::to_string(&raw).map_err(|_| ApiCallError::internal("Invalid blob request".into()))?);
        let query = crate::bindings::rest_client::encode_query_params(params);
        for server in access.servers {
            if !server.url.starts_with("https://") { return Err(ApiCallError::internal("Invalid blob server scheme".into())); }
            let response = self.rest_client.velin_blob_binary(format!("{}/rest/storage/blobservice{}", server.url.trim_end_matches('/'), query),
                HttpMethod::GET, RestClientOptions { body: None, headers: Default::default(), suspension_behavior: None }).await?;
            if !(200..300).contains(&response.status) { continue; }
            let bytes = response.body.ok_or_else(|| ApiCallError::internal("Missing blob body".into()))?;
            return velin_parse_blobs(&bytes, &blobs);
        }
        Err(ApiCallError::internal("Blob download failed".into()))
    }
}

/// Each BlobId is an aggregate. It needs its own random ID, or serialization fails before the request.
fn velin_blob_get_in(blobs: &[crate::entities::generated::sys::Blob])
    -> Result<crate::entities::generated::storage::BlobGetIn, ApiCallError> {
    use crate::entities::generated::storage::{BlobGetIn, BlobId};
    let archive = &blobs[0].archiveId;
    if blobs.iter().any(|b| &b.archiveId != archive) { return Err(ApiCallError::internal("Mixed blob archives".into())); }
    Ok(BlobGetIn { _format: 0, archiveId: archive.clone(), blobId: None,
        blobIds: blobs.iter().map(|b| BlobId { _id: Some(crate::mail_facade::velin_aggregate_id()), blobId: b.blobId.clone() }).collect() })
}

fn velin_parse_blobs(bytes: &[u8], blobs: &[crate::entities::generated::sys::Blob]) -> Result<Vec<Vec<u8>>, ApiCallError> {
    use base64::Engine;
    let invalid = || ApiCallError::internal("Invalid blob framing".into());
    if bytes.len() < 4 || bytes.len() > 21 * 1024 * 1024 || blobs.len() > 100 { return Err(invalid()); }
    let count = u32::from_be_bytes(bytes[..4].try_into().map_err(|_| invalid())?) as usize;
    if count != blobs.len() { return Err(invalid()); }
    let mut offset = 4;
    let mut chunks = std::collections::HashMap::new();
    for _ in 0..count {
        let header = bytes.get(offset..offset + 19).ok_or_else(invalid)?;
        let id = crate::util::BASE64_EXT.encode(&header[..9]);
        let size = u32::from_be_bytes(header[15..19].try_into().map_err(|_| invalid())?) as usize;
        let expected = blobs.iter().find(|b| b.blobId.as_str() == id).ok_or_else(invalid)?;
        if expected.size < 0 || size as u64 != expected.size as u64 { return Err(invalid()); }
        offset += 19;
        let end = offset.checked_add(size).ok_or_else(invalid)?;
        let data = bytes.get(offset..end).ok_or_else(invalid)?;
        if chunks.insert(id, data).is_some() { return Err(invalid()); }
        offset = end;
    }
    if offset != bytes.len() { return Err(invalid()); }
    blobs.iter().map(|b| chunks.remove(b.blobId.as_str()).map(|v| v.to_vec()).ok_or_else(invalid)).collect()
}

#[cfg(test)]
mod velin_blob_tests {
    use super::*;
    #[test]
    fn velin_blob_framing_preserves_order_and_bounds_before_copy() {
        use crate::entities::generated::sys::Blob;
        let blob = Blob { _id: None, archiveId: "archive".to_owned().into(), size: 3,
            blobId: "------------".to_owned().into() };
        let mut response = vec![0, 0, 0, 1];
        response.extend_from_slice(&[0; 15]);
        response.extend_from_slice(&3u32.to_be_bytes());
        response.extend_from_slice(&[1, 2, 3]);
        assert_eq!(velin_parse_blobs(&response, &[blob.clone()]).unwrap(), vec![vec![1, 2, 3]]);
        for end in 0..response.len() { assert!(velin_parse_blobs(&response[..end], &[blob.clone()]).is_err()); }
        response.push(0);
        assert!(velin_parse_blobs(&response, &[blob.clone()]).is_err());
        response.pop();
        response[19..23].copy_from_slice(&u32::MAX.to_be_bytes());
        assert!(velin_parse_blobs(&response, &[blob]).is_err());
    }
    #[test]
    fn velin_blob_request_serializes_and_rejects_mixed_archives() {
        use crate::entities::generated::{storage::BlobGetIn, sys::Blob};
        let blob = |archive: &str, id: &str| Blob { _id: None, archiveId: archive.to_owned().into(), size: 3, blobId: id.to_owned().into() };
        let input = velin_blob_get_in(&[blob("archive", "------------"), blob("archive", "-----------0")]).unwrap();
        assert!(input.blobIds[0]._id.is_some() && input.blobIds[0]._id != input.blobIds[1]._id);
        let models = crate::velin_session_transfer::velin_session_transfer_tests::provider(false);
        let parsed = crate::instance_mapper::InstanceMapper::new(models.clone()).serialize_entity(input).unwrap();
        crate::json_serializer::JsonSerializer::new(models).serialize(&BlobGetIn::type_ref(), parsed).unwrap();
        assert!(velin_blob_get_in(&[blob("archive", "------------"), blob("other", "-----------0")]).is_err());
    }
    #[test]
    fn velin_blob_framing_rejects_truncation_and_extra_data() {
        assert!(velin_parse_blobs(&[], &[]).is_err());
        assert!(velin_parse_blobs(&[0,0,0,1], &[]).is_err());
        assert!(velin_parse_blobs(&[0,0,0,0,1], &[]).is_err());
        assert!(velin_parse_blobs(&[0,0,0,0], &[]).unwrap().is_empty());
    }
}
