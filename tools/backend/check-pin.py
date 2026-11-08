#!/usr/bin/env python3
"""Check release inputs and Kotlin wire constants. No network requests."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
source = Path(sys.argv[1])
pin = json.loads((root / 'tools/backend/upstream.json').read_text())
for name, digest in pin['sha256'].items():
    assert hashlib.sha256((source / name).read_bytes()).hexdigest() == digest, name
versions = {app: re.search(r'version:\s*(\d+)', (source / f'src/entities/{app}/ModelInfo.ts').read_text())[1]
            for app in ('sys', 'tutanota')}
for app, model_version in versions.items():
    rust_models = json.loads((source / f'tuta-sdk/rust/sdk/src/type_models/{app}.json').read_text())
    assert all(model['version'] == int(model_version) for model in rust_models.values()), 'Rust model version drift'
version = re.search(r'version = "([^"]+)"', (source / 'Cargo.toml').read_text())[1]
assert pin['tag'] == f'tutanota-release-{version}', 'Only release tags are supported'
assert re.fullmatch('[0-9a-f]{40}', pin['commit']), 'Full commit required'
expected = f'''package app.velin.core.sdk

/** Checked by tools/backend/check-pin.py against the pinned release. */
object TutaRelease {{
    const val COMMIT = "{pin['commit']}"
    const val CLIENT_VERSION = "{version}"
    const val SYS_MODEL_VERSION = {versions['sys']}
    const val TUTANOTA_MODEL_VERSION = {versions['tutanota']}
}}
'''
assert (root / 'core/sdk/src/main/kotlin/app/velin/core/sdk/TutaRelease.kt').read_text() == expected, 'Kotlin release constants differ'
# Every numeric JSON key in AuthService must retain its released field name.
models = subprocess.check_output(['node', '--input-type=module', '-e',
    'import fs from "node:fs"; const s=fs.readFileSync(process.argv[1],"utf8"); console.log(JSON.stringify(eval(s.replace("const typeModels =", "(").replace("export default typeModels", ")"))))',
    str(source / 'src/entities/sys/TypeModels.js')], text=True)
models = json.loads(models)
fields = {}
for model in models.values():
    for section in ('values', 'associations'):
        for key, value in model.get(section, {}).items():
            fields[key] = value['name']
used = set(re.findall(r'"(\d+)"', (root / 'core/data/src/main/kotlin/app/velin/core/data/AuthService.kt').read_text())) - {'0','1'}
assert {key: fields[key] for key in used} == pin['authFields'], 'Auth wire fields differ'
print('Release pin, model versions, client version, and auth wire fields verified')
