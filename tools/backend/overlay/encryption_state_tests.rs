
#[cfg(test)]
mod velin_encryption_state_tests {
    use super::*;
    use crate::util::test_utils::create_test_entity;

    #[test]
    fn velin_rollout_scheme_is_unknown_until_loaded_and_never_downgrades() {
        let user = UserFacade::new(Arc::new(KeyCache::default()), create_test_entity());
        assert_eq!(user.velin_encryption_scheme(), None);
        assert_eq!(user.velin_apply_encryption_rollouts(&[]), VelinEncryptionScheme::AesCbc);
        let rollout = crate::entities::generated::sys::Rollout { rolloutType: 5, ..create_test_entity() };
        assert_eq!(user.velin_apply_encryption_rollouts(&[rollout]), VelinEncryptionScheme::Aead);
        assert_eq!(user.velin_apply_encryption_rollouts(&[]), VelinEncryptionScheme::Aead);
        let other = UserFacade::new(Arc::new(KeyCache::default()), create_test_entity());
        assert_eq!(other.velin_encryption_scheme(), None);
    }

    #[test]
    fn velin_transfer_metadata_survives_model_loading() {
        use crate::type_model_provider::CLIENT_TYPE_MODEL;
        use crate::metamodel::{AppName, TypeId};
        let types = &CLIENT_TYPE_MODEL.apps[&AppName::Tutanota].types;
        let mail = &types[&TypeId::from(2058)];
        assert_eq!(mail.target_type_id, Some(97));
        assert_eq!(mail.values.values().find(|v| v.name == "subject").unwrap().transferred_attribute_id, Some(105));
        assert_eq!(mail.associations.values().find(|a| a.name == "sender").unwrap().transferred_attribute_id, Some(111));
        assert_eq!(types[&TypeId::from(2052)].target_type_id, Some(1298));
    }
}
