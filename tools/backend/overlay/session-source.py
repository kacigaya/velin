"""Add WithSessionKey encryption to the exported release SDK."""
from pathlib import Path
import sys

root = Path(sys.argv[1]) / 'tuta-sdk/rust/sdk/src'
overlay = Path(__file__).parent


def replace(path, old, new):
    text = path.read_text()
    assert text.count(old) == 1, f'Pinned source changed: {path.name}'
    path.write_text(text.replace(old, new))


(root / 'velin_session_transfer.rs').write_text((overlay / 'session_transfer.rs').read_text() + (overlay / 'session_transfer_tests.rs').read_text())
(root / 'velin_drafts.json').write_bytes((overlay.parent / 'vectors/drafts.json').read_bytes())
(root / 'velin_draft_compression.rs').write_text((overlay / 'draft_compression.rs').read_text())
replace(root / 'lib.rs', 'mod user_facade;', 'mod user_facade;\nmod velin_session_transfer;\nmod velin_draft_compression;')
replace(root / 'entities/entity_facade.rs', '\tfn parse_decrypted_value(', '\tpub(crate) fn parse_decrypted_value(')
# Keep existing entity reads intact here. The draft reload path can call the
# strict session-key decryptor with its canonical persistent type context.
replace(root / 'services.rs', 'pub struct ExtraServiceParams {', '''pub struct ExtraServiceParams {
    pub(crate) velin_session_transfer: Option<crate::velin_session_transfer::VelinSessionTransferEncryption>,''')
path = root / 'services/service_executor.rs'
replace(path, 'let encrypted_parsed_entity = if type_model.is_encrypted() {', '''let encrypted_parsed_entity = if let Some(params) = &extra_service_params.velin_session_transfer {
                crate::velin_session_transfer::encrypt(&self.type_model_provider, type_model, &parsed_entity, params,
                    &crypto_primitives::randomizer_facade::RandomizerFacade::from_core(rand_core::OsRng))?
            } else if type_model.is_encrypted() {''')
replace(path, 'Some(serde_json::to_vec::<RawEntity>(&input).map_err(|e| {', '''Some(if extra_service_params.velin_session_transfer.is_some() {
                        crate::velin_session_transfer::wire_json(&input)?
                    } else { serde_json::to_vec::<RawEntity>(&input).map_err(|e| {''')
replace(path, 'ApiCallError::internal_with_err(e, "failed to serialize input to string")\n\t\t\t\t\t})?)', 'ApiCallError::internal_with_err(e, "failed to serialize input to string")\n\t\t\t\t\t})? })')
