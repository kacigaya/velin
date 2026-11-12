
/// Errors that a later read can resolve. The Kotlin trust store reports its failures with this fixed message.
fn velin_temporary_auth_error(error: &ApiCallError) -> bool {
	match error {
		ApiCallError::ServerResponseError { source } => matches!(source, HttpError::ConnectionError | HttpError::RequestTimeoutError
			| HttpError::LockedError | HttpError::TooManyRequestsError { .. } | HttpError::InternalServerError
			| HttpError::BadGatewayError | HttpError::ServiceUnavailableError { .. }
			| HttpError::NotAuthenticatedError | HttpError::SessionExpiredError),
		// A failed TLS setup means the key was never read, so no key comparison failed.
		ApiCallError::RestClient { source } => matches!(source, RestClientError::NetworkError
			| RestClientError::FailedHandshake | RestClientError::FailedTlsSetup),
		ApiCallError::InternalSdkError { error_message } => error_message == "Identity trust storage failed",
		_ => false,
	}
}

#[cfg(test)]
mod velin_sender_error_tests {
	use super::*;
	use crate::crypto::asymmetric_crypto_facade::MockAsymmetricCryptoFacade;
	use crate::crypto::crypto_facade::MockCryptoFacade;
	use crate::entities::entity_facade::MockEntityFacade;
	use crate::entity_client::MockEntityClient;
	use crate::key_loader_facade::MockKeyLoaderFacade;
	use crate::util::test_utils::mock_type_model_provider;

	async fn status(error: ApiCallError) -> Result<EncryptionAuthStatus, ApiCallError> {
		let mut facade = MockAsymmetricCryptoFacade::default();
		facade.expect_authenticate_sender().return_once(move |_, _| Err(AsymmetricCryptoError::PublicKeyLoadingError(
			PublicKeyLoadingError::KeyLoadingError(error))));
		let client = CryptoEntityClient::new(Arc::new(MockEntityClient::default()), Arc::new(MockEntityFacade::default()),
			Arc::new(MockCryptoFacade::default()), Arc::new(InstanceMapper::new(Arc::new(mock_type_model_provider()))),
			Arc::new(facade), Arc::new(MockKeyLoaderFacade::default()));
		client.tuta_crypt_authenticate_sender_of_main_instance("ann@tuta.com".into(),
			Versioned { version: 0, object: X25519PublicKey::from_bytes(&[9; 32]).unwrap() }).await
	}

	#[tokio::test]
	async fn velin_temporary_failures_fail_the_read_and_permanent_ones_fail_the_check() {
		for error in [ApiCallError::ServerResponseError { source: HttpError::TooManyRequestsError { suspension_time_sec: None } },
			ApiCallError::ServerResponseError { source: HttpError::ServiceUnavailableError { suspension_time_sec: None } },
			ApiCallError::ServerResponseError { source: HttpError::RequestTimeoutError },
			ApiCallError::RestClient { source: RestClientError::FailedHandshake },
			ApiCallError::RestClient { source: RestClientError::FailedTlsSetup },
			ApiCallError::InternalSdkError { error_message: "Identity trust storage failed".into() }] {
			assert!(status(error).await.is_err());
		}
		// A deleted alias stays a failed check, as upstream does.
		assert_eq!(status(ApiCallError::ServerResponseError { source: HttpError::NotFoundError }).await.unwrap(),
			EncryptionAuthStatus::TutacryptAuthenticationFailed);
	}
}
