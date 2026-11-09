
impl MailFacade {
    async fn velin_service_encryption_scheme(&self) -> Result<crate::user_facade::VelinEncryptionScheme, ApiCallError> {
        if let Some(scheme) = self.user_facade.velin_encryption_scheme() { return Ok(scheme) }
        let result = self.service_executor.get::<crate::services::generated::sys::RolloutService>((), Default::default()).await?;
        Ok(self.user_facade.velin_apply_encryption_rollouts(&result.rollouts))
    }
}

#[cfg(test)]
mod velin_rollout_tests {
    use super::*;
    use crate::crypto_entity_client::MockCryptoEntityClient;
    use crate::services::service_executor::MockResolvingServiceExecutor;
    use crate::user_facade::{MockUserFacade, VelinEncryptionScheme};

    #[tokio::test]
    async fn velin_rollout_failure_does_not_select_a_scheme() {
        let mut user = MockUserFacade::default();
        user.expect_velin_encryption_scheme().return_const(None);
        user.expect_velin_apply_encryption_rollouts().never();
        let mut services = MockResolvingServiceExecutor::default();
        services.expect_get::<crate::services::generated::sys::RolloutService>()
            .return_once(|_, _| Err(ApiCallError::internal("Test rollout failure".into())));
        let facade = MailFacade::new(Arc::new(MockCryptoEntityClient::default()), Arc::new(user), Arc::new(services));
        assert!(facade.velin_service_encryption_scheme().await.is_err());
    }

    #[tokio::test]
    async fn velin_known_scheme_does_not_repeat_rollout_request() {
        let mut user = MockUserFacade::default();
        user.expect_velin_encryption_scheme().return_const(Some(VelinEncryptionScheme::Aead));
        let facade = MailFacade::new(Arc::new(MockCryptoEntityClient::default()), Arc::new(user), Arc::new(MockResolvingServiceExecutor::default()));
        assert_eq!(facade.velin_service_encryption_scheme().await.unwrap(), VelinEncryptionScheme::Aead);
    }
}
