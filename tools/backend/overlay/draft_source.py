"""Expose draft create, update, and reload through the pinned SDK."""
from pathlib import Path
import sys
root = Path(sys.argv[1]) / 'tuta-sdk/rust/sdk/src'
overlay = Path(__file__).parent


def replace(path, old, new):
    text = path.read_text()
    assert text.count(old) == 1, f'Pinned source changed: {path.name}'
    path.write_text(text.replace(old, new))


replace(root / 'crypto_entity_client.rs', '\tpub fn serialize_entity<', (overlay / 'draft_client.rs').read_text() + '\n\tpub fn serialize_entity<')
with (root / 'mail_facade.rs').open('a') as output:
    output.write((overlay / 'drafts.rs').read_text() + (overlay / 'draft_tests.rs').read_text())

replace(root / 'instance_mapper.rs', '\ttype_model_provider: Arc<TypeModelProvider>,', '\tpub(crate) type_model_provider: Arc<TypeModelProvider>,')
replace(root / 'lib.rs', 'pub enum ApiCallError {', '''pub enum ApiCallError {
    #[error("Draft operation is unsupported")]
    VelinDraftUnsupported,''')

replace(root / 'entities/entity_facade.rs', '''self.decrypt_and_map_inner(type_model, entity, &resolved_session_key.session_key)?;''', '''if crate::velin_session_transfer::contains_session_aead(&self.type_model_provider, type_model, &entity) {
                crate::velin_session_transfer::decrypt(&self.type_model_provider, type_model, &entity,
                    &resolved_session_key.session_key, &self.randomizer_facade)?
            } else {
                self.decrypt_and_map_inner(type_model, entity, &resolved_session_key.session_key)?
            };''')

replace(root / 'services/service_executor.rs', 'if type_model.marked_encrypted() {', 'if type_model.marked_encrypted() && !velin_plain_draft_update_return(type_model) {')
with (root / 'services/service_executor.rs').open('a') as output:
    output.write((overlay / 'draft_response.rs').read_text())
