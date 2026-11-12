	/// Velin keeps pinned sender identities per account. Kotlin sets the store once per session.
	pub fn velin_set_sender_trust_store(&self, store: Arc<dyn crate::mail_facade::VelinIdentityTrustStore>) {
		if let Ok(mut slot) = self.velin_trust.write() { *slot = Some(store); }
	}

	/// PublicEncryptionKeyProvider.ts and KeyVerificationFacade.ts: the sender key of a TutaCrypt mail must be the
	/// requested version, equal the key in the message, and carry a valid signature of the pinned identity.
	/// One server response serves all checks, so the server cannot answer each check differently.
	#[allow(clippy::needless_lifetimes)] // explicit lifetime needed for automock
	async fn velin_authenticate_sender<'a>(&self, store: Arc<dyn crate::mail_facade::VelinIdentityTrustStore>, address: String,
		sender_identity_pub_key: Versioned<&'a X25519PublicKey>,
	) -> Result<EncryptionAuthStatus, AsymmetricCryptoError> {
		use crate::entities::generated::sys::{IdentityKeyGetIn, IdentityKeyGetOut, PublicKeyGetIn};
		use crate::mail_facade::VelinTrustedIdentity;
		use crate::rest_error::HttpError;
		use crate::services::generated::sys::IdentityKeyService;
		// Recipient lookup stores pins under the same cleaned address.
		let address = address.trim().to_lowercase();
		let loading = |e: ApiCallError| AsymmetricCryptoError::PublicKeyLoadingError(PublicKeyLoadingError::KeyLoadingError(e));
		let failed = EncryptionAuthStatus::TutacryptAuthenticationFailed;
		let version = crate::util::convert_version_to_i64(sender_identity_pub_key.version);
		let key = self.service_executor.get::<PublicKeyService>(PublicKeyGetIn {
			_format: 0, version: Some(version), identifierType: 0, identifier: address.clone(),
		}, ExtraServiceParams::default()).await.map_err(loading)?;
		if key.pubKeyVersion != version || key.pubEccKey.as_deref() != Some(sender_identity_pub_key.object.as_bytes()) {
			return Ok(failed);
		}
		let pinned = |t: VelinTrustedIdentity| IdentityKeyGetOut { _format: 0, publicIdentityKey: t.key, publicIdentityKeyVersion: t.version };
		// A trust storage failure is local and temporary. It fails the read, so it never becomes a mismatch.
		let identity = match store.load(address.clone()).await {
			Ok(Some(trusted)) => Some(pinned(trusted)),
			Ok(None) => match self.service_executor.get::<IdentityKeyService>(IdentityKeyGetIn {
				_format: 0, version: None, identifierType: 0, identifier: address.clone(),
			}, ExtraServiceParams::default()).await {
				Ok(identity) if identity.publicIdentityKey.len() == 32 && (0..=255).contains(&identity.publicIdentityKeyVersion) => {
					match store.trust_on_first_use(address.clone(), VelinTrustedIdentity {
						key: identity.publicIdentityKey, version: identity.publicIdentityKeyVersion, manual: false,
					}).await {
						Ok(trusted) => Some(pinned(trusted)),
						Err(error) => return Err(loading(error)),
					}
				},
				Ok(_) => return Ok(failed),
				Err(ApiCallError::ServerResponseError { source: HttpError::NotFoundError }) => None,
				Err(error) => return Err(loading(error)),
			},
			Err(error) => return Err(loading(error)),
		};
		// 0: legacy key without identity, 1: valid signature, 2: mismatch.
		Ok(match crate::mail_facade::velin_verify_recipient_key(&key, identity.as_ref()) {
			Ok(0 | 1) => EncryptionAuthStatus::TutacryptAuthenticationSucceeded,
			_ => failed,
		})
	}

