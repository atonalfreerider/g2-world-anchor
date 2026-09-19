import { createHash } from 'node:crypto'
import { readFile, readdir, stat } from 'node:fs/promises'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const packagePath = resolve(projectRoot, process.argv[2] ?? '../g2-world-anchor-companion.ehpk')
const manifest = JSON.parse(await readFile(join(projectRoot, 'app.json'), 'utf8'))
const sdkPackage = JSON.parse(await readFile(join(projectRoot, 'node_modules/@evenrealities/even_hub_sdk/package.json'), 'utf8'))

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

assert(/^com\.[a-z0-9.]+$/.test(manifest.package_id), 'Invalid package_id')
assert(/^\d+\.\d+\.\d+$/.test(manifest.version), 'Version must be semver')
assert(manifest.entrypoint === 'index.html', 'Entrypoint must be index.html')
assert(Array.isArray(manifest.permissions) && manifest.permissions.length === 0, 'Companion should need no permissions')
assert(manifest.min_sdk_version === sdkPackage.version, 'Manifest SDK version does not match installed SDK')
assert(manifest.min_app_version === sdkPackage.minAppVersion, 'Manifest app floor does not match SDK requirement')

const entrypoint = join(projectRoot, 'dist', manifest.entrypoint)
const html = await readFile(entrypoint, 'utf8')
const localReferences = [...html.matchAll(/(?:src|href)="([^"#]+)"/g)]
  .map(match => match[1])
  .filter(value => !/^(?:[a-z]+:|\/\/)/i.test(value))

for (const reference of localReferences) {
  const clean = reference.replace(/^\.\//, '').split('?')[0]
  await stat(join(projectRoot, 'dist', clean))
}

async function collectFiles(directory, prefix = '') {
  const files = []
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const relative = join(prefix, entry.name)
    if (entry.isDirectory()) files.push(...await collectFiles(join(directory, entry.name), relative))
    else files.push(relative)
  }
  return files
}

const distFiles = await collectFiles(join(projectRoot, 'dist'))
assert(distFiles.includes('index.html'), 'dist/index.html is missing')
assert(distFiles.some(file => file.endsWith('.js')), 'Built JavaScript bundle is missing')

const ehpk = await readFile(packagePath)
assert(ehpk.length > 1024, 'EHPK is unexpectedly small')
assert(ehpk.subarray(0, 4).toString('ascii') === 'EHPK', 'EHPK signature is invalid')

const result = {
  artifact: basename(packagePath),
  package_id: manifest.package_id,
  version: manifest.version,
  sdk: manifest.min_sdk_version,
  minimum_even_app: manifest.min_app_version,
  permissions: manifest.permissions,
  dist_files: distFiles.length,
  bytes: ehpk.length,
  sha256: createHash('sha256').update(ehpk).digest('hex'),
}

console.log(JSON.stringify(result, null, 2))
