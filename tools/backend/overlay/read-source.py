"""Apply read methods to exported release source only."""
from pathlib import Path
import sys
root = Path(sys.argv[1]) / 'tuta-sdk/rust/sdk/src'
overlay = Path(__file__).parent
path = root / 'entity_client.rs'
text = path.read_text()
text += '\n' + (overlay / 'entity_read.rs').read_text()
needle = '\t\tpub fn get_type_model_provider()'
assert text.count(needle) == 1
text = text.replace(needle, '''        pub async fn velin_blob(&self, type_ref: &TypeRef, id: &crate::IdTupleGenerated,
            access: crate::entities::generated::storage::BlobServerAccessInfo) -> Result<ParsedEntity, ApiCallError>;
''' + needle)
path.write_text(text)
path = root / 'crypto_entity_client.rs'
text = path.read_text()
needle = '\tpub fn serialize_entity<'
assert text.count(needle) == 1
path.write_text(text.replace(needle, (overlay / 'crypto_read.rs').read_text() + needle))

path = root / 'bindings/rest_client.rs'
text = path.read_text()
needle = '    async fn velin_blob_binary'  # new callback exists only in this overlay
assert needle not in text
marker = '\tasync fn request_binary('
start = text.index(marker)
end = text.index(';', start) + 1
method = text[start:end].replace('request_binary(', 'velin_blob_binary(').removesuffix(';') + ' { self.request_binary(url, method, options).await }'
text = text[:end] + '\n' + method + text[end:]
path.write_text(text)

path = root / 'bindings/suspendable_rest_client.rs'
text = path.read_text()
start = text.index('\tasync fn request_binary(', text.index('impl RestClient for'))
end = text.index('\n\t}\n}', start) + 3
method = text[start:end].replace('request_binary(', 'velin_blob_binary(').replace('self.inner.request_binary(', 'self.inner.velin_blob_binary(')
path.write_text(text[:end] + '\n' + method + text[end:])

# Bound decrypted compressed strings before the upstream decoder allocates output.
path = root / 'entities/entity_facade.rs'
text = path.read_text()
old = 'let mut uncompressed_bytes = lz4_flex::decompress(compressed_bytes, compressed_bytes.len());'
assert text.count(old) == 1
text = text.replace(old, '''const VELIN_TEXT_LIMIT: usize = 32 * 1024 * 1024;
        let mut uncompressed_bytes = lz4_flex::decompress(compressed_bytes, compressed_bytes.len().min(VELIN_TEXT_LIMIT));''')
old = '''actual,
			expected: _,'''
assert text.count(old) == 1
text = text.replace(old, 'actual, expected,')
old = 'uncompressed_bytes = lz4_flex::decompress(compressed_bytes, actual * 2);'
assert text.count(old) == 1
text = text.replace(old, '''if actual >= VELIN_TEXT_LIMIT || expected > VELIN_TEXT_LIMIT {
                return Err(DecompressError::OutputTooSmall { actual: VELIN_TEXT_LIMIT, expected });
            }
            uncompressed_bytes = lz4_flex::decompress(compressed_bytes, actual.saturating_mul(2).min(VELIN_TEXT_LIMIT));''')
text += '''
#[cfg(test)]
mod velin_text_limit_tests {
    use super::*;
    #[test]
    fn velin_rejects_oversized_compressed_text() {
        let compressed = lz4_flex::compress(&vec![b'a'; 32 * 1024 * 1024 + 1]);
        assert!(EntityFacadeImpl::lz4_decompress_decrypted_bytes(&compressed).is_err());
        let compressed = lz4_flex::compress(b"small mail body");
        assert_eq!(EntityFacadeImpl::lz4_decompress_decrypted_bytes(&compressed).unwrap(), b"small mail body");
    }
}
'''
path.write_text(text)
