// Velin exports for the pinned SDK. Upstream mail_facade.rs:56-89 and
// crypto_entity_client.rs:72-132 supply typed, decrypted entity access.
use futures::FutureExt;
use std::panic::AssertUnwindSafe;

#[uniffi::export]
impl MailFacade {
    pub async fn velin_folders(&self) -> Result<Vec<MailSet>, ApiCallError> {
        AssertUnwindSafe(async {
            let mailbox = self.load_user_mailbox().await?;
            self.crypto_entity_client
                .load_all::<MailSet>(&mailbox.mailSets.mailSets, ListLoadDirection::ASC)
                .await
        })
        .catch_unwind()
        .await
        .unwrap_or_else(|_| Err(ApiCallError::internal("Velin folder read failed".into())))
    }

    // CounterFacade.ts:11-19; monitor/Utils.ts:4. Read only; never repairs counters.
    pub async fn velin_unread(
        &self,
        group: String,
        folder: GeneratedId,
    ) -> Result<i64, ApiCallError> {
        use crate::entities::generated::monitor::ReadCounterData;
        use crate::services::generated::monitor::CounterService;
        AssertUnwindSafe(async {
            let response = self
                .service_executor
                .get::<CounterService>(
                    ReadCounterData {
                        _format: 0,
                        rowName: group,
                        columnName: Some(folder.clone()),
                        counterType: 2,
                    },
                    Default::default(),
                )
                .await?;
            response
                .counterValues
                .into_iter()
                .find(|c| c.counterId == folder)
                .map(|c| c.value)
                .filter(|v| *v >= 0)
                .ok_or_else(|| ApiCallError::internal("Invalid unread counter".into()))
        })
        .catch_unwind()
        .await
        .unwrap_or_else(|_| Err(ApiCallError::internal("Velin counter read failed".into())))
    }

    // EntityUtils.ts:56 uses 340 underscores as the exclusive upper custom ID.
    pub async fn velin_entries(
        &self,
        list: GeneratedId,
        start: Option<String>,
    ) -> Result<Vec<crate::entities::generated::tutanota::MailSetEntry>, ApiCallError> {
        AssertUnwindSafe(async {
            let start = crate::CustomId(start.unwrap_or_else(|| "_".repeat(340)));
            self.crypto_entity_client
                .load_range(&list, &start, 50, ListLoadDirection::DESC)
                .await
        })
        .catch_unwind()
        .await
        .unwrap_or_else(|_| Err(ApiCallError::internal("Velin entry read failed".into())))
    }

    pub async fn velin_details(&self, id: IdTupleGenerated) -> Result<crate::entities::generated::tutanota::MailDetails, ApiCallError> {
        use crate::entities::generated::storage::{BlobAccessTokenPostIn, BlobReadData};
        use crate::services::generated::storage::BlobAccessTokenService;
        AssertUnwindSafe(async {
            let mail: Mail = self.crypto_entity_client.load(&id).await?;
            if !mail._errors.is_empty() { return Err(ApiCallError::internal("Mail decryption failed".into())); }
            let access = if mail.mailDetailsDraft.is_some() { None } else {
                let details = mail.mailDetails.as_ref().ok_or_else(|| ApiCallError::internal("Missing mail details".into()))?;
                Some(self.service_executor.post::<BlobAccessTokenService>(BlobAccessTokenPostIn {
                    _format: 0, archiveDataType: None, write: None,
                    read: Some(BlobReadData { _id: None, archiveId: details.list_id.clone(), instanceListId: None, instanceIds: vec![] }),
                }, Default::default()).await?.blobAccessInfo)
            };
            self.crypto_entity_client.velin_details(mail, access).await
        }).catch_unwind().await.unwrap_or_else(|_| Err(ApiCallError::internal("Velin details read failed".into())))
    }

    // ConversationViewModel.ts:232 loads the entry list, not a linked chain.
    pub async fn velin_conversation(&self, list: GeneratedId) -> Result<Vec<crate::entities::generated::tutanota::ConversationEntry>, ApiCallError> {
        AssertUnwindSafe(self.crypto_entity_client.load_all(&list, ListLoadDirection::ASC))
            .catch_unwind().await.unwrap_or_else(|_| Err(ApiCallError::internal("Velin conversation read failed".into())))
    }

    pub async fn velin_attachment(&self, mail: IdTupleGenerated, id: IdTupleGenerated) -> Result<crate::entities::generated::tutanota::TutanotaFile, ApiCallError> {
        AssertUnwindSafe(self.crypto_entity_client.velin_file(mail, id)).catch_unwind().await
            .unwrap_or_else(|_| Err(ApiCallError::internal("Attachment read failed".into())))
    }
    pub async fn velin_attachment_content(&self, mail: IdTupleGenerated, id: IdTupleGenerated) -> Result<Vec<u8>, ApiCallError> {
        use crate::entities::generated::storage::{BlobAccessTokenPostIn, BlobReadData, InstanceId};
        use crate::services::generated::storage::BlobAccessTokenService;
        AssertUnwindSafe(async {
            let file = self.crypto_entity_client.velin_file(mail, id.clone()).await?;
            if file.size < 0 || file.size > 20 * 1024 * 1024 || file.blobs.len() > 100 {
                return Err(ApiCallError::internal("Attachment exceeds limit".into()));
            }
            let Some(blob) = file.blobs.first() else {
                return if file.size == 0 { Ok(vec![]) } else { Err(ApiCallError::internal("Missing file blobs".into())) };
            };
            let access = self.service_executor.post::<BlobAccessTokenService>(BlobAccessTokenPostIn {
                _format: 0, archiveDataType: Some(1), write: None,
                read: Some(BlobReadData { _id: None, archiveId: blob.archiveId.clone(), instanceListId: Some(id.list_id),
                    instanceIds: vec![InstanceId { _id: None, instanceId: Some(id.element_id) }] }),
            }, Default::default()).await?.blobAccessInfo;
            self.crypto_entity_client.velin_file_content(file, access).await
        }).catch_unwind().await.unwrap_or_else(|_| Err(ApiCallError::internal("Attachment read failed".into())))
    }

    pub async fn velin_mail(&self, id: IdTupleGenerated) -> Result<Mail, ApiCallError> {
        AssertUnwindSafe(self.crypto_entity_client.load(&id))
            .catch_unwind()
            .await
            .unwrap_or_else(|_| Err(ApiCallError::internal("Velin mail read failed".into())))
    }
}

#[cfg(test)]
mod velin_read_tests {
    use super::*;
    use crate::crypto_entity_client::MockCryptoEntityClient;
    use crate::entities::generated::tutanota::{Mail, MailSetEntry};
    use crate::services::service_executor::MockResolvingServiceExecutor;
    use crate::user_facade::MockUserFacade;
    use mockall::predicate::eq;

    #[tokio::test]
    async fn entries_use_custom_descending_cursor() {
        let mut client = MockCryptoEntityClient::default();
        client
            .expect_load_range::<MailSetEntry, crate::CustomId>()
            .with(
                eq(GeneratedId::from("list".to_owned())),
                eq(crate::CustomId("_".repeat(340))),
                eq(50),
                eq(ListLoadDirection::DESC),
            )
            .returning(|_, _, _, _| Ok(vec![]));
        let facade = MailFacade::new(
            Arc::new(client),
            Arc::new(MockUserFacade::default()),
            Arc::new(MockResolvingServiceExecutor::default()),
        );
        assert!(facade
            .velin_entries("list".to_owned().into(), None)
            .await
            .unwrap()
            .is_empty());
    }

    #[tokio::test]
    async fn unread_uses_mail_group_and_folder_column() {
        use crate::entities::generated::monitor::{CounterValue, ReadCounterReturn};
        use crate::services::generated::monitor::CounterService;
        let mut executor = MockResolvingServiceExecutor::default();
        executor
            .expect_get::<CounterService>()
            .withf(|data, _| {
                data.rowName == "group"
                    && data.columnName.as_ref().unwrap().as_str() == "folder"
                    && data.counterType == 2
            })
            .returning(|_, _| {
                Ok(ReadCounterReturn {
                    _format: 0,
                    value: None,
                    counterValues: vec![CounterValue {
                        _id: None,
                        counterId: "folder".to_owned().into(),
                        value: 7,
                    }],
                })
            });
        let facade = MailFacade::new(
            Arc::new(MockCryptoEntityClient::default()),
            Arc::new(MockUserFacade::default()),
            Arc::new(executor),
        );
        assert_eq!(
            facade
                .velin_unread("group".into(), "folder".to_owned().into())
                .await
                .unwrap(),
            7
        );
    }

    #[tokio::test]
    async fn panics_return_typed_error() {
        let mut client = MockCryptoEntityClient::default();
        client
            .expect_load::<Mail, IdTupleGenerated>()
            .returning(|_| panic!("fixture panic"));
        let facade = MailFacade::new(
            Arc::new(client),
            Arc::new(MockUserFacade::default()),
            Arc::new(MockResolvingServiceExecutor::default()),
        );
        assert!(facade
            .velin_mail(IdTupleGenerated::new(
                "list".to_owned().into(),
                "mail".to_owned().into()
            ))
            .await
            .is_err());
    }
}

#[cfg(test)]
mod velin_wire_tests {
    use super::*;

    #[test]
    fn numeric_mail_set_fields_preserve_parent_and_entries() {
        // Parsed entity JSON uses generated numeric attributes (tutanota.rs:837-865).
        let folder: MailSet = serde_json::from_str(
            r##"{
            "431":{"list_id":"folders","element_id":"child"},
            "432":"permissions","433":0,"434":null,"435":"Projects","436":0,
            "589":"mailGroup","1479":"#112233","1847":null,
            "439":{"list_id":"folders","element_id":"parent"},"1459":"entries"
        }"##,
        )
        .unwrap();
        assert_eq!(folder.name, "Projects");
        assert_eq!(folder.folderType, 0);
        assert_eq!(folder.entries.as_str(), "entries");
        assert_eq!(folder.parentFolder.unwrap().element_id.as_str(), "parent");
        assert!(folder._errors.is_empty());
    }
}

#[cfg(test)]
mod velin_details_tests {
    use super::*;
    use crate::crypto_entity_client::MockCryptoEntityClient;
    use crate::entities::generated::storage::{BlobAccessTokenPostOut, BlobServerAccessInfo};
    use crate::entities::generated::tutanota::MailDetails;
    use crate::services::generated::storage::BlobAccessTokenService;
    use crate::services::service_executor::MockResolvingServiceExecutor;
    use crate::user_facade::MockUserFacade;
    use crate::util::test_utils::create_test_entity;

    #[tokio::test]
    async fn velin_received_details_request_archive_read_token() {
        let mut client = MockCryptoEntityClient::default();
        client.expect_load::<Mail, IdTupleGenerated>().returning(|_| Ok(Mail {
            mailDetails: Some(IdTupleGenerated::new("archive".to_owned().into(), "details".to_owned().into())),
            mailDetailsDraft: None, _ownerEncSessionKey: Some(vec![1, 2, 3]),
            _ownerKeyVersion: Some(7), ..create_test_entity()
        }));
        client.expect_velin_details().withf(|mail, access| {
            mail._ownerEncSessionKey == Some(vec![1, 2, 3]) && mail._ownerKeyVersion == Some(7) && access.is_some()
        }).returning(|_, _| Ok(create_test_entity::<MailDetails>()));
        let mut executor = MockResolvingServiceExecutor::default();
        executor.expect_post::<BlobAccessTokenService>().withf(|data, _| {
            let read = data.read.as_ref().unwrap();
            data.archiveDataType.is_none() && data.write.is_none() &&
                read.archiveId.as_str() == "archive" && read.instanceListId.is_none() && read.instanceIds.is_empty()
        }).returning(|_, _| Ok(BlobAccessTokenPostOut { _format: 0, blobAccessInfo: create_test_entity::<BlobServerAccessInfo>() }));
        let facade = MailFacade::new(Arc::new(client), Arc::new(MockUserFacade::default()), Arc::new(executor));
        assert!(facade.velin_details(IdTupleGenerated::new("mails".to_owned().into(), "one".to_owned().into())).await.is_ok());
    }

    #[tokio::test]
    async fn velin_draft_details_do_not_request_blob_token() {
        let mut client = MockCryptoEntityClient::default();
        client.expect_load::<Mail, IdTupleGenerated>().returning(|_| Ok(Mail {
            mailDetailsDraft: Some(IdTupleGenerated::new("drafts".to_owned().into(), "details".to_owned().into())),
            ..create_test_entity()
        }));
        client.expect_velin_details().withf(|_, access| access.is_none())
            .returning(|_, _| Ok(create_test_entity::<MailDetails>()));
        let facade = MailFacade::new(Arc::new(client), Arc::new(MockUserFacade::default()), Arc::new(MockResolvingServiceExecutor::default()));
        assert!(facade.velin_details(IdTupleGenerated::new("mails".to_owned().into(), "one".to_owned().into())).await.is_ok());
    }
}
