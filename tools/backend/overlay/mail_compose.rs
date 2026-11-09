// Pinned mail_facade.rs:139-181 selects the address-owning GroupInfo.
#[derive(uniffi::Record, Clone)]
pub struct VelinSender {
    pub address: String,
    pub name: String,
}

#[uniffi::export]
impl MailFacade {
    pub async fn velin_senders(&self) -> Result<Vec<VelinSender>, ApiCallError> {
        use crate::entities::generated::tutanota::MailboxProperties;
        AssertUnwindSafe(async {
            let user = self.user_facade.get_user();
            let user_info: GroupInfo = self.crypto_entity_client.load(&user.userGroup.groupInfo).await?;
            if user_info._id.as_ref() != Some(&user.userGroup.groupInfo) || !user_info._errors.is_empty() {
                return Err(ApiCallError::internal("Sender decryption failed".into()));
            }
            let mut senders = Vec::new();
            for membership in user.memberships.iter().filter(|m| m.group_type() == GroupType::Mail) {
                let group: Group = self.crypto_entity_client.load(&membership.group).await?;
                if group._id.as_ref() != Some(&membership.group) {
                    return Err(ApiCallError::internal("Invalid sender group".into()));
                }
                let Some(info_id) = velin_sender_info_id(&group, &user, membership) else { continue };
                let info: GroupInfo = if info_id == &user.userGroup.groupInfo { user_info.clone() }
                    else { self.crypto_entity_client.load(info_id).await? };
                if info._id.as_ref() != Some(info_id) || !info._errors.is_empty() {
                    return Err(ApiCallError::internal("Sender decryption failed".into()));
                }
                let root: MailboxGroupRoot = self.crypto_entity_client.load(&membership.group).await?;
                if root._id.as_ref() != Some(&membership.group) {
                    return Err(ApiCallError::internal("Invalid sender mailbox".into()));
                }
                let properties: Option<MailboxProperties> = if let Some(id) = root.mailboxProperties {
                    let properties: MailboxProperties = self.crypto_entity_client.load(&id).await?;
                    if properties._id.as_ref() != Some(&id) || !properties._errors.is_empty() || properties.mailAddressProperties.iter().any(|p| !p._errors.is_empty()) {
                        return Err(ApiCallError::internal("Sender decryption failed".into()));
                    }
                    Some(properties)
                } else { None };
                for address in get_enabled_mail_addresses_for_group_info(&info) {
                    if senders.iter().any(|s: &VelinSender| s.address == address) { continue }
                    let name = properties.as_ref().and_then(|p| p.mailAddressProperties.iter()
                        .find(|p| p.mailAddress == address).map(|p| p.senderName.clone()))
                        .unwrap_or_else(|| user_info.name.clone());
                    senders.push(VelinSender { address, name });
                }
            }
            Ok(senders)
        }).catch_unwind().await.unwrap_or_else(|_| Err(ApiCallError::internal("Sender read failed".into())))
    }
}

fn velin_sender_info_id<'a>(group: &Group, user: &'a crate::entities::generated::sys::User,
    membership: &'a crate::entities::generated::sys::GroupMembership,
) -> Option<&'a IdTupleGenerated> {
    match (&group.user, &user._id) {
        (None, _) => Some(&membership.groupInfo),
        (Some(owner), Some(id)) if owner == id => Some(&user.userGroup.groupInfo),
        _ => None,
    }
}

#[cfg(test)]
mod velin_sender_tests {
    use super::*;
    use crate::entities::generated::sys::{GroupMembership, MailAddressAlias, User};
    use crate::crypto_entity_client::MockCryptoEntityClient;
    use crate::services::service_executor::MockResolvingServiceExecutor;
    use crate::user_facade::MockUserFacade;
    use crate::util::test_utils::create_test_entity;

    #[test]
    fn velin_sender_info_matches_address_ownership() {
        let membership: GroupMembership = create_test_entity();
        let user: User = User { _id: Some("user".to_owned().into()), ..create_test_entity() };
        let mut group: Group = Group { user: None, ..create_test_entity() };
        assert_eq!(velin_sender_info_id(&group, &user, &membership), Some(&membership.groupInfo));
        group.user = user._id.clone();
        assert_eq!(velin_sender_info_id(&group, &user, &membership), Some(&user.userGroup.groupInfo));
        group.user = Some("other".to_owned().into());
        assert!(velin_sender_info_id(&group, &user, &membership).is_none());
    }

    #[tokio::test]
    async fn velin_senders_reject_mismatched_or_failed_user_info() {
        for bad_id in [false, true] {
            let user: User = create_test_entity();
            let mut users = MockUserFacade::default();
            users.expect_get_user().return_const(Arc::new(user));
            let mut client = MockCryptoEntityClient::default();
            client.expect_load::<GroupInfo, IdTupleGenerated>().returning(move |id| {
                let mut info: GroupInfo = create_test_entity();
                info._id = if bad_id { None } else { Some(id.clone()) };
                if !bad_id { info._errors.insert("name".into(), crate::element_value::ElementValue::String("error".into())); }
                Ok(info)
            });
            let facade = MailFacade::new(Arc::new(client), Arc::new(users), Arc::new(MockResolvingServiceExecutor::default()));
            assert!(facade.velin_senders().await.is_err());
        }
    }

    #[tokio::test]
    async fn velin_senders_load_real_enabled_aliases_without_writes() {
        use crate::entities::generated::tutanota::{MailAddressProperties, MailboxProperties};
        let mut user: User = create_test_entity();
        user._id = Some("user".to_owned().into());
        user.memberships = vec![GroupMembership { groupType: Some(GroupType::Mail as i64), ..create_test_entity() }];
        let info_id = user.userGroup.groupInfo.clone();
        let mut users = MockUserFacade::default();
        users.expect_get_user().return_const(Arc::new(user));
        let mut client = MockCryptoEntityClient::default();
        client.expect_load::<Group, GeneratedId>().returning(|id| Ok(Group { _id: Some(id.clone()), user: Some("user".to_owned().into()), ..create_test_entity() }));
        client.expect_load::<GroupInfo, IdTupleGenerated>().withf(move |id| id == &info_id).returning(|id| Ok(GroupInfo {
            _id: Some(id.clone()), name: "Default".into(), mailAddress: Some("primary@example.invalid".into()),
            mailAddressAliases: vec![MailAddressAlias { mailAddress: "alias@example.invalid".into(), enabled: true, ..create_test_entity() },
                MailAddressAlias { mailAddress: "disabled@example.invalid".into(), enabled: false, ..create_test_entity() }],
            ..create_test_entity()
        }));
        client.expect_load::<MailboxGroupRoot, GeneratedId>().returning(|id| Ok(MailboxGroupRoot {
            _id: Some(id.clone()), mailboxProperties: Some("properties".to_owned().into()), ..create_test_entity()
        }));
        client.expect_load::<MailboxProperties, GeneratedId>().returning(|id| Ok(MailboxProperties {
            _id: Some(id.clone()), mailAddressProperties: vec![MailAddressProperties {
                mailAddress: "alias@example.invalid".into(), senderName: "Alias name".into(), ..create_test_entity()
            }], ..create_test_entity()
        }));
        let facade = MailFacade::new(Arc::new(client), Arc::new(users), Arc::new(MockResolvingServiceExecutor::default()));
        let senders = facade.velin_senders().await.unwrap();
        assert_eq!(senders.len(), 2);
        assert_eq!(senders[0].address, "alias@example.invalid");
        assert_eq!(senders[0].name, "Alias name");
        assert_eq!(senders[1].address, "primary@example.invalid");
        assert_eq!(senders[1].name, "Default");
    }
}
