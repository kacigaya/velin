import { createRequire } from 'node:module'
import { readFileSync, realpathSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { resolve, join } from 'node:path'
import { pathToFileURL, fileURLToPath } from 'node:url'
import { tmpdir } from 'node:os'
import { execFileSync } from 'node:child_process'

const root = realpathSync(process.env.TUTA_RELEASE)
const pin = '332e57461cdc4fe4a4ad9bdc8555f56df02931c0'
if (execFileSync('git', ['-C', root, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim() !== pin) {
  throw new Error('Vector source must be the pinned release')
}
execFileSync('git', ['-C', root, 'diff', '--exit-code', 'HEAD'], { stdio: 'inherit' })
const require = createRequire(join(root, 'package.json'))
const { rolldown } = await import(pathToFileURL(require.resolve('rolldown')).href)
const paths = JSON.parse(readFileSync(join(root, 'tsconfig_common.json'), 'utf8')).compilerOptions.paths
const alias = Object.fromEntries(Object.entries(paths).map(([key, value]) => [key, resolve(root, value[0])]))
alias['@release'] = root
const temp = mkdtempSync(join(tmpdir(), 'velin-vectors-'))
try {
  const bundle = await rolldown({
    input: fileURLToPath(new URL('./drafts.ts', import.meta.url)), platform: 'node',
    resolve: { alias, extensions: ['.ts', '.js', '.json'] },
    treeshake: { moduleSideEffects: false },
    // The crypto barrel exports Ed25519 too. It is unused here. Fail below if
    // tree shaking leaves this unbuilt WebAssembly import in the draft program.
    external: id => id.includes('crypto-primitives/crypto_primitives'),
  })
  const result = await bundle.write({ file: join(temp, 'drafts.mjs'), format: 'esm' })
  for (const chunk of result.output) {
    if (chunk.type === 'chunk' && chunk.imports.some(id => !id.startsWith('node:'))) {
      throw new Error('Vector program still requires an external module')
    }
  }
  await bundle.close()
  // No HTTP client is supplied to the release code. Block fetch as well.
  globalThis.fetch = () => { throw new Error('Network is forbidden in vector generation') }
  const { output } = await import(pathToFileURL(join(temp, 'drafts.mjs')).href)
  writeFileSync(new URL('./drafts.json', import.meta.url), JSON.stringify(output, null, 2) + '\n')
} finally {
  rmSync(temp, { recursive: true, force: true })
}
