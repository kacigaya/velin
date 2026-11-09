"""Apply draft protocol changes to the exported pinned release."""
from pathlib import Path
import sys

root = Path(sys.argv[1]) / 'tuta-sdk/rust/sdk/src'
overlay = Path(__file__).parent


def replace(path, old, new):
    text = path.read_text()
    assert text.count(old) == 1, f'Pinned source changed: {path.name}'
    path.write_text(text.replace(old, new))


path = root / 'metamodel.rs'
replace(path, 'pub struct ModelValue {', '''pub struct ModelValue {
    #[serde(default, rename = "transferredAttributeId")]
    pub transferred_attribute_id: Option<u64>,''')
replace(path, 'pub struct ModelAssociation {', '''pub struct ModelAssociation {
    #[serde(default)]
    pub transferred_attribute_id: Option<u64>,''')
replace(path, 'pub struct TypeModel {', '''pub struct TypeModel {
    #[serde(default)]
    pub target_type_id: Option<u64>,''')
# Existing test fixtures construct these records directly.
path = root / 'util/test_utils.rs'
text = path.read_text().replace('TypeModel {\n\t\tid:', 'TypeModel {\n        target_type_id: None,\n\t\tid:')
text = text.replace('ModelValue {\n', 'ModelValue {\n                    transferred_attribute_id: None,\n')
path.write_text(text)
replace(root / 'entities/entity_facade.rs', '\t\tModelValue {\n\t\t\tid:', '\t\tModelValue {\n            transferred_attribute_id: None,\n\t\t\tid:')

path = root / 'user_facade.rs'
replace(path, 'pub struct UserFacade {', '''#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum VelinEncryptionScheme { AesCbc, Aead }

pub struct UserFacade {
    velin_encryption_scheme: RwLock<Option<VelinEncryptionScheme>>,''')
replace(path, 'UserFacade {\n\t\t\tuser:', 'UserFacade {\n            velin_encryption_scheme: RwLock::new(None),\n\t\t\tuser:')
replace(path, '\tpub fn get_user(&self)', '''    pub(crate) fn velin_encryption_scheme(&self) -> Option<VelinEncryptionScheme> {
        *self.velin_encryption_scheme.read().unwrap()
    }

    // RolloutType.EncryptionOfAttributesViaAead is 5 in this release.
    // Unknown state must not select CBC before authenticated rollout loading.
    pub(crate) fn velin_apply_encryption_rollouts(&self, rollouts: &[crate::entities::generated::sys::Rollout]) -> VelinEncryptionScheme {
        let mut scheme = self.velin_encryption_scheme.write().unwrap();
        let selected = if *scheme == Some(VelinEncryptionScheme::Aead) || rollouts.iter().any(|r| r.rolloutType == 5) {
            VelinEncryptionScheme::Aead
        } else { VelinEncryptionScheme::AesCbc };
        *scheme = Some(selected);
        selected
    }

\tpub fn get_user(&self)''')
with path.open('a') as output:
    output.write((overlay / 'encryption_state_tests.rs').read_text())
with (root / 'mail_facade.rs').open('a') as output:
    output.write((overlay / 'encryption_rollout.rs').read_text())

path = root / 'metamodel.rs'
text = path.read_text()
marker = 'pub struct AttributeId(u64);'
start = text.rfind('#[derive(', 0, text.index(marker))
end = text.index(')]', start)
text = text[:end] + ', PartialOrd, Ord' + text[end:]
path.write_text(text)
