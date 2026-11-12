
#[cfg(test)]
mod velin_sender_auth_tests {
	use super::*;
	use crate::crypto::public_key_provider::MockPublicKeyProvider;
	use crate::entities::generated::sys::{IdentityKeyGetOut, PublicKeyGetOut, PublicKeySignature};
	use crate::key_loader_facade::MockKeyLoaderFacade;
	use crate::mail_facade::{VelinIdentityTrustStore, VelinTrustedIdentity};
	use crate::rest_error::HttpError;
	use crate::services::generated::sys::IdentityKeyService;
	use crate::services::service_executor::MockServiceExecutor;
	use crate::tutanota_constants::PublicKeyIdentifierType;
	use crypto_primitives::compatibility_test_utils::get_compatibility_test_data;
	use crypto_primitives::ed25519::Ed25519KeyPair;
	use crypto_primitives::randomizer_facade::test_util::make_thread_rng_facade;
	use std::sync::Mutex;

	struct Store(Mutex<Option<VelinTrustedIdentity>>);
	#[async_trait::async_trait]
	impl VelinIdentityTrustStore for Store {
		async fn load(&self, _: String) -> Result<Option<VelinTrustedIdentity>, ApiCallError> { Ok(self.0.lock().unwrap().clone()) }
		async fn trust_on_first_use(&self, _: String, identity: VelinTrustedIdentity) -> Result<VelinTrustedIdentity, ApiCallError> {
			Ok(self.0.lock().unwrap().get_or_insert(identity).clone())
		}
	}
	const ECC: [u8; 32] = [9; 32];
	fn signed_key(pair: Option<&Ed25519KeyPair>, version: i64) -> PublicKeyGetOut {
		let mut key = PublicKeyGetOut { _format: 0, pubKeyVersion: version, pubEccKey: Some(ECC.to_vec()), pubKyberKey: None,
			pubRsaKey: Some(get_compatibility_test_data().rsa_encryption_tests.remove(0).public_key), signature: None };
		if let Some(pair) = pair {
			let mut unversioned = key.clone();
			unversioned.pubKeyVersion = 0;
			key.signature = Some(PublicKeySignature { _id: None, signingKeyVersion: 0, signatureType: 2, publicKeyVersion: version,
				signature: pair.private_key.sign(&crate::mail_facade::velin_public_key_message(&unversioned).unwrap()).to_bytes().to_vec() });
		}
		key
	}
	/// A TutaCrypt key may have any version. It is signed at the version it carries.
	fn pq_key(pair: &Ed25519KeyPair, version: i64) -> PublicKeyGetOut {
		let mut key = PublicKeyGetOut { _format: 0, pubKeyVersion: version, pubEccKey: Some(ECC.to_vec()), pubRsaKey: None,
			pubKyberKey: Some(get_compatibility_test_data().kyber_encryption_tests.remove(0).public_key), signature: None };
		key.signature = Some(PublicKeySignature { _id: None, signingKeyVersion: 0, signatureType: 2, publicKeyVersion: version,
			signature: pair.private_key.sign(&crate::mail_facade::velin_public_key_message(&key).unwrap()).to_bytes().to_vec() });
		key
	}
	fn identity(pair: &Ed25519KeyPair) -> IdentityKeyGetOut {
		IdentityKeyGetOut { _format: 0, publicIdentityKey: pair.public_key.to_bytes().to_vec(), publicIdentityKeyVersion: 0 }
	}
	async fn authenticate(services: MockServiceExecutor, store: Option<Arc<Store>>, address: &str, message_key: [u8; 32])
		-> Result<EncryptionAuthStatus, AsymmetricCryptoError> {
		authenticate_version(services, store, address, message_key, 0).await
	}
	async fn authenticate_version(services: MockServiceExecutor, store: Option<Arc<Store>>, address: &str, message_key: [u8; 32], version: u64)
		-> Result<EncryptionAuthStatus, AsymmetricCryptoError> {
		let facade = AsymmetricCryptoFacade::new(Arc::new(MockKeyLoaderFacade::default()), make_thread_rng_facade(),
			Arc::new(services), Arc::new(MockPublicKeyProvider::default()));
		if let Some(store) = store { facade.velin_set_sender_trust_store(store); }
		let key = X25519PublicKey::from_bytes(&message_key).unwrap();
		facade.authenticate_sender(PublicKeyIdentifier { identifier: address.into(), identifier_type: PublicKeyIdentifierType::MailAddress },
			Versioned { version, object: &key }).await
	}
	fn random_pair() -> Ed25519KeyPair {
		Ed25519KeyPair::generate(&mut crypto_primitives::randomizer_facade::RandomizerFacade::from_core(rand_core::OsRng))
	}

	#[tokio::test]
	async fn velin_first_sender_identity_is_pinned_then_enforced() {
		let pair = random_pair();
		let store = Arc::new(Store(Mutex::new(None)));
		let mut services = MockServiceExecutor::default();
		let key = signed_key(Some(&pair), 0);
		services.expect_get::<PublicKeyService>().returning(move |input, _| {
			assert_eq!(input.version, Some(0));
			Ok(key.clone())
		});
		let fetched = identity(&pair);
		services.expect_get::<IdentityKeyService>().times(1).returning(move |_, _| Ok(fetched.clone()));
		assert_eq!(authenticate(services, Some(store.clone()), "ann@tuta.com", ECC).await.unwrap(), EncryptionAuthStatus::TutacryptAuthenticationSucceeded);
		assert!(store.0.lock().unwrap().is_some());

		// A key signed by another identity fails against the pinned one, without fetching a new identity.
		let mut services = MockServiceExecutor::default();
		let other = signed_key(Some(&random_pair()), 0);
		services.expect_get::<PublicKeyService>().returning(move |_, _| Ok(other.clone()));
		services.expect_get::<IdentityKeyService>().never();
		assert_eq!(authenticate(services, Some(store), "ann@tuta.com", ECC).await.unwrap(), EncryptionAuthStatus::TutacryptAuthenticationFailed);
	}

	#[tokio::test]
	async fn velin_wrong_message_key_or_version_fails_and_legacy_key_passes() {
		let pair = random_pair();
		let mut services = MockServiceExecutor::default();
		let key = signed_key(Some(&pair), 0);
		services.expect_get::<PublicKeyService>().returning(move |_, _| Ok(key.clone()));
		services.expect_get::<IdentityKeyService>().never();
		assert_eq!(authenticate(services, Some(Arc::new(Store(Mutex::new(None)))), "ann@tuta.com", [8; 32]).await.unwrap(),
			EncryptionAuthStatus::TutacryptAuthenticationFailed);
		// A valid version-1 key passes when version 1 is requested, and fails when the server sends it for version 0.
		for (requested, expected) in [(1, EncryptionAuthStatus::TutacryptAuthenticationSucceeded), (0, EncryptionAuthStatus::TutacryptAuthenticationFailed)] {
			let mut services = MockServiceExecutor::default();
			let key = pq_key(&pair, 1);
			services.expect_get::<PublicKeyService>().returning(move |_, _| Ok(key.clone()));
			let fetched = identity(&pair);
			services.expect_get::<IdentityKeyService>().returning(move |_, _| Ok(fetched.clone()));
			assert_eq!(authenticate_version(services, Some(Arc::new(Store(Mutex::new(None)))), "ann@tuta.com", ECC, requested).await.unwrap(), expected);
		}
		let mut services = MockServiceExecutor::default();
		let key = signed_key(None, 0);
		services.expect_get::<PublicKeyService>().returning(move |_, _| Ok(key.clone()));
		services.expect_get::<IdentityKeyService>().returning(|_, _| Err(ApiCallError::ServerResponseError { source: HttpError::NotFoundError }));
		assert_eq!(authenticate(services, Some(Arc::new(Store(Mutex::new(None)))), "ann@tuta.com", ECC).await.unwrap(),
			EncryptionAuthStatus::TutacryptAuthenticationSucceeded);
	}

	struct BrokenStore(bool);
	#[async_trait::async_trait]
	impl VelinIdentityTrustStore for BrokenStore {
		async fn load(&self, _: String) -> Result<Option<VelinTrustedIdentity>, ApiCallError> {
			if self.0 { Err(ApiCallError::InternalSdkError { error_message: "Identity trust storage failed".into() }) } else { Ok(None) }
		}
		async fn trust_on_first_use(&self, _: String, _: VelinTrustedIdentity) -> Result<VelinTrustedIdentity, ApiCallError> {
			Err(ApiCallError::InternalSdkError { error_message: "Identity trust storage failed".into() })
		}
	}
	// Store failures must fail the read, never become a mismatch: first a failed load, then a failed first pin.
	#[tokio::test]
	async fn velin_trust_store_failures_fail_the_read() {
		for broken_load in [true, false] {
			let pair = random_pair();
			let mut services = MockServiceExecutor::default();
			let key = signed_key(Some(&pair), 0);
			services.expect_get::<PublicKeyService>().returning(move |_, _| Ok(key.clone()));
			let fetched = identity(&pair);
			services.expect_get::<IdentityKeyService>().returning(move |_, _| Ok(fetched.clone()));
			let facade = AsymmetricCryptoFacade::new(Arc::new(MockKeyLoaderFacade::default()), make_thread_rng_facade(),
				Arc::new(services), Arc::new(MockPublicKeyProvider::default()));
			facade.velin_set_sender_trust_store(Arc::new(BrokenStore(broken_load)));
			let key = X25519PublicKey::from_bytes(&ECC).unwrap();
			let result = facade.authenticate_sender(PublicKeyIdentifier { identifier: "ann@tuta.com".into(),
				identifier_type: PublicKeyIdentifierType::MailAddress }, Versioned { version: 0, object: &key }).await;
			assert!(matches!(result, Err(AsymmetricCryptoError::PublicKeyLoadingError(PublicKeyLoadingError::KeyLoadingError(
				ApiCallError::InternalSdkError { .. })))), "{broken_load}");
		}
	}
	#[tokio::test]
	async fn velin_network_errors_stay_temporary_and_system_sender_keeps_release_check() {
		let mut services = MockServiceExecutor::default();
		services.expect_get::<PublicKeyService>().returning(|_, _| Err(ApiCallError::ServerResponseError { source: HttpError::ConnectionError }));
		assert!(matches!(authenticate(services, Some(Arc::new(Store(Mutex::new(None)))), "ann@tuta.com", ECC).await,
			Err(AsymmetricCryptoError::PublicKeyLoadingError(PublicKeyLoadingError::KeyLoadingError(ApiCallError::ServerResponseError {
				source: HttpError::ConnectionError })))));
		// The system address has no pinned identity. It keeps the release key-equality check through the provider.
		let mut services = MockServiceExecutor::default();
		services.expect_get::<PublicKeyService>().never();
		let mut provider = MockPublicKeyProvider::default();
		provider.expect_load_versioned_pub_key().returning(|_, _| Err(PublicKeyLoadingError::KeyValidationError("none".into())));
		let facade = AsymmetricCryptoFacade::new(Arc::new(MockKeyLoaderFacade::default()), make_thread_rng_facade(),
			Arc::new(services), Arc::new(provider));
		facade.velin_set_sender_trust_store(Arc::new(Store(Mutex::new(None))));
		let key = X25519PublicKey::from_bytes(&ECC).unwrap();
		assert!(facade.authenticate_sender(PublicKeyIdentifier { identifier: crate::tutanota_constants::SYSTEM_GROUP_MAIL_ADDRESS.into(),
			identifier_type: PublicKeyIdentifierType::MailAddress }, Versioned { version: 0, object: &key }).await.is_err());
	}
}
