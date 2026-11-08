"""Fail closed for the release SDK's unfinished secure-external branch."""
from pathlib import Path
import sys

root = Path(sys.argv[1]) / 'tuta-sdk/rust/sdk/src'

def replace(path, old, new):
    source = path.read_text()
    assert source.count(old) == 1, f'Pinned source changed: {path}'
    path.write_text(source.replace(old, new))

path = root / 'crypto/crypto_facade.rs'
replace(path, 'todo!("secure external resolveWithGroupReference")',
        'return Err(SessionKeyResolutionError { reason: VELIN_SECURE_EXTERNAL.to_owned() });')
with path.open('a') as output:
    output.write('''
// Velin keeps this unsupported branch distinct across the FFI boundary.
const VELIN_SECURE_EXTERNAL: &str = "Velin secure external mail unsupported";
impl SessionKeyResolutionError {
    pub(crate) fn velin_secure_external(&self) -> bool {
        self.reason == VELIN_SECURE_EXTERNAL
    }
}
''')
replace(root / 'lib.rs', 'pub enum ApiCallError {', '''pub enum ApiCallError {
    #[error("Secure external mail is unsupported")]
    VelinSecureExternalUnsupported,''')
replace(root / 'crypto_entity_client.rs', '''.map_err(|error| {
				let Ok(id_field_attribute_id)''', '''.map_err(|error| {
                if error.velin_secure_external() {
                    return ApiCallError::VelinSecureExternalUnsupported;
                }
				let Ok(id_field_attribute_id)''')
replace(path, '\tfn make_raw_mail(', '''
    #[tokio::test]
    async fn velin_secure_external_is_typed_failure() {
        let randomizer = make_thread_rng_facade();
        let constants = BucketKeyConstants::new(&randomizer);
        let mail = Mail {
            _ownerGroup: Some(constants.key_group.clone()),
            bucketKey: Some(BucketKey {
                groupEncBucketKey: Some(vec![1; 32]),
                pubEncBucketKey: None,
                ..create_test_entity()
            }),
            ..create_test_entity()
        };
        let facade = make_crypto_facade(randomizer, constants.group_key,
            constants.sender_key_version, None, vec![], None);
        let result = facade.resolve_session_key(
            &typed_entity_to_parsed_entity(mail), &get_mail_type_model()).await;
        assert!(result.err().expect("must fail").velin_secure_external());
    }

\tfn make_raw_mail(''')
