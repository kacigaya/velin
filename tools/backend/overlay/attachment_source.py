from pathlib import Path
import sys

root = Path(sys.argv[1]) / 'tuta-sdk/rust/sdk/src'
overlay = Path(__file__).parent


def replace(path, old, new):
    text = path.read_text()
    assert text.count(old) == 1, f'Pinned source changed: {path.name}'
    path.write_text(text.replace(old, new))


path = root / 'mail_facade.rs'
replace(path, 'pub struct MailFacade {\n', 'pub struct MailFacade {\n    velin_blob: Option<Arc<crate::blobs::blob_facade::BlobFacade>>,\n')
replace(path, '\t\tMailFacade {\n            velin_trust:', '\t\tMailFacade {\n            velin_blob: None,\n            velin_trust:')
replace(path, '/// Maximum number of mails that can be moved', '''impl MailFacade {
    #[must_use]
    pub(crate) fn velin_with_blob(mut self, blob: Arc<crate::blobs::blob_facade::BlobFacade>) -> Self {
        self.velin_blob = Some(blob);
        self
    }
}

/// Maximum number of mails that can be moved''')
with path.open('a') as output:
    output.write((overlay / 'attachment_tests.rs').read_text())
replace(root / 'lib.rs', '''		MailFacade::new(
			self.crypto_entity_client.clone(),
			self.user_facade.clone(),
			self.service_executor.clone(),
		)''', '''		MailFacade::new(
			self.crypto_entity_client.clone(),
			self.user_facade.clone(),
			self.service_executor.clone(),
		)
		.velin_with_blob(self.blob_facade.clone())''')
replace(root / 'blobs/blob_facade.rs', "Box<dyn ExactSizeIterator<Item = &'slice [u8]> + 'slice>", "Box<dyn ExactSizeIterator<Item = &'slice [u8]> + Send + 'slice>")
