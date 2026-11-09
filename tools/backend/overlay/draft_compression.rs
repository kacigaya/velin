/*
Copyright (c) 2012 Pierre Curto

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
 */

// Port of src/platform-kit/instance-pipeline/Compression.ts:142 at the release pin.
pub(crate) fn compress(source: &[u8]) -> Vec<u8> {
    if source.is_empty() { return Vec::new() }
    fn length_bytes(mut length: usize, output: &mut Vec<u8>) {
        while length >= 255 { output.push(255); length -= 255; }
        output.push(length as u8);
    }
    let mut output = Vec::with_capacity(source.len() + source.len() / 255 + 16);
    let mut hashes = vec![0usize; 1 << 16];
    let (mut position, mut anchor, mut attempts) = (0usize, 0usize, (1usize << 6) + 3);
    let limit = source.len().saturating_sub(12);
    while position + 4 < limit {
        let sequence = u32::from_le_bytes([source[position], source[position + 1], source[position + 2], source[position + 3]]);
        let hash = (sequence.wrapping_mul(2654435761) >> 16) as usize;
        let reference = hashes[hash].checked_sub(1);
        hashes[hash] = position + 1;
        let Some(mut reference) = reference.filter(|&reference| position - reference <= 65535 && source[reference..reference + 4] == source[position..position + 4]) else {
            let step = attempts >> 6;
            attempts += 1;
            position += step;
            continue;
        };
        attempts = (1 << 6) + 3;
        let literals = position - anchor;
        let offset = position - reference;
        position += 4;
        reference += 4;
        let start_match = position;
        while position < limit && source[position] == source[reference] { position += 1; reference += 1; }
        let matched = position - start_match;
        output.push(((literals.min(15) << 4) + matched.min(15)) as u8);
        if literals >= 15 { length_bytes(literals - 15, &mut output); }
        output.extend_from_slice(&source[anchor..anchor + literals]);
        output.extend_from_slice(&(offset as u16).to_le_bytes());
        if matched >= 15 { length_bytes(matched - 15, &mut output); }
        anchor = position;
    }
    let literals = source.len() - anchor;
    output.push((literals.min(15) << 4) as u8);
    if literals >= 15 { length_bytes(literals - 15, &mut output); }
    output.extend_from_slice(&source[anchor..]);
    output
}
