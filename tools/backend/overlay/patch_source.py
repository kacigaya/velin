"""Send entity updates as PATCH with per-value encryption, and read values that use the instance key."""
from pathlib import Path
import sys

root = Path(sys.argv[1]) / 'tuta-sdk/rust/sdk/src'
overlay = Path(__file__).parent


def replace(path, old, new):
    text = path.read_text()
    assert text.count(old) == 1, f'Pinned source changed: {path.name}'
    path.write_text(text.replace(old, new))


(root / 'velin_entity_patch.rs').write_text((overlay / 'entity_patch.rs').read_text() + (overlay / 'entity_patch_tests.rs').read_text())
(root / 'velin_patches.json').write_bytes((overlay.parent / 'vectors/patches.json').read_bytes())
replace(root / 'lib.rs', 'mod velin_session_transfer;', 'mod velin_session_transfer;\nmod velin_entity_patch;')

# EntityRestClient.ts:713 sends PATCH. The Kotlin transport maps the variant by name.
replace(root / 'bindings/rest_client.rs', '\tPUT,\n\tDELETE,\n}', '\tPUT,\n\tDELETE,\n\tPATCH,\n}')
replace(root / 'net/native_rest_client.rs', '\t\t\tHttpMethod::DELETE => req.method("DELETE"),',
        '\t\t\tHttpMethod::DELETE => req.method("DELETE"),\n\t\t\tHttpMethod::PATCH => req.method("PATCH"),')

path = root / 'entity_client.rs'
with path.open('a') as output:
    output.write((overlay / 'entity_patch_rest.rs').read_text())
replace(path, '\t\tpub fn get_type_model_provider()', '''        pub async fn velin_patch(&self, type_ref: &TypeRef, id: &crate::IdTupleGenerated,
            patch: crate::entities::generated::sys::PatchList) -> Result<(), ApiCallError>;
\t\tpub fn get_type_model_provider()''')

path = root / 'crypto_entity_client.rs'
replace(path, '\tpub fn serialize_entity<', (overlay / 'entity_patch_client.rs').read_text() + '\tpub fn serialize_entity<')
# A value patched with the instance key fails the release's session-key pass with a MAC error for the whole
# instance. Decrypt those values first, blank them for that pass, then restore their plaintext.
replace(path, '''				let mut decrypted_entity =
					self.entity_facade
						.decrypt_and_map(type_model, parsed_entity, session_key)?;
''', '''				let velin_instance_values = self.velin_instance_key_values(type_model, &parsed_entity).await?;
				let mut parsed_entity = parsed_entity;
				crate::velin_entity_patch::blank_instance_values(&mut parsed_entity, &velin_instance_values);
				let mut decrypted_entity =
					self.entity_facade
						.decrypt_and_map(type_model, parsed_entity, session_key)?;
				crate::velin_entity_patch::apply_instance_values(&mut decrypted_entity, velin_instance_values);
''')
