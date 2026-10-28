// Checks the landing page's own <script> against a stub DOM, so the download links keep working
// after edits. Runs offline against fixtures; `LIVE=1 node scripts/verify-site.mjs` also exercises
// the real GitHub API, which is worth doing before publishing a change to the page.
//
// No dependencies: Node >= 20, nothing to install. Wired into the code-quality CI job.
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const siteDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'site')
const script = readFileSync(join(siteDir, 'index.html'), 'utf8').match(
    /<script type="module">([\s\S]*?)<\/script>/,
)[1]

const mk = () => ({
  children: [], _t: '',
  get textContent() { return this._t },
  set textContent(v) { this._t = v },
  append(...xs) { this.children.push(...xs) },
  replaceChildren(...xs) { this.children = xs },
  get text() { return this._t + this.children.map((c) => c.text).join('') },
})
const el = (tag) => Object.assign(mk(), { tag, href: '', className: '' })

async function render(fetchImpl) {
  const downloads = el('ul'), version = el('p')
  globalThis.document = {
    getElementById: (id) => (id === 'downloads' ? downloads : version),
    createElement: el,
    createTextNode: (t) => Object.assign(mk(), { _t: t }),
  }
  globalThis.fetch = fetchImpl
  const warn = console.warn
  console.warn = () => {}          // the failure path logs; keep the output readable
  try {
    await new Function(`return (async () => {${script}})()`).call(globalThis)
  } finally {
    console.warn = warn
  }
  return {
    rows: downloads.children.map((li) => {
      const a = li.children[0]
      return { label: a.children[0]?.text ?? a.text, href: a.href }
    }),
    version: version.text,
  }
}

const asset = (name, mb) => ({
  name,
  size: mb * 1048576,
  browser_download_url: `https://example.test/${name}`,
})
const release = (over) => ({
  tag_name: 'v1.0.0', prerelease: false, draft: false,
  html_url: 'https://example.test/notes', assets: [], ...over,
})

let failures = 0
const check = (name, condition, detail) => {
  console.log(`  ${condition ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
  if (!condition) failures++
}

// 1. The real API, opt-in: every release is a beta today, so the newest is shown and labelled one.
if (process.env.LIVE) {
  console.log('\n1. live API')
  const { rows, version } = await render(fetch)
  console.log('   ', rows.map((r) => r.label).join(', '))
  console.log('   ', JSON.stringify(version))
  check('renders installers plus checksums', rows.length >= 2, `${rows.length} rows`)
  check('links point at downloads', rows.every((r) => r.href.startsWith('https://github.com/')))
} else {
  console.log('\n1. live API — skipped (set LIVE=1 to include it)')
}

// 2. A stable release exists but is older than the newest beta: the stable must win.
console.log('\n2. stable v1.1.0 older than beta v1.2.0-beta')
{
  const body = JSON.stringify([
    release({ tag_name: 'v1.2.0-beta', prerelease: true, assets: [asset('app-1.2.0.dmg', 90)] }),
    release({ tag_name: 'v1.1.0', assets: [asset('app-1.1.0.dmg', 80)] }),
  ])
  const { rows, version } = await render(async () => ({ ok: true, json: async () => JSON.parse(body) }))
  check('chose the stable release', version.includes('v1.1.0'), version)
  check('not labelled a beta', version.startsWith('Latest: '), version)
  check('its installer is offered', rows[0].href.includes('app-1.1.0.dmg'), rows[0].href)
}

// 3. An installer format the page has never heard of is still published.
console.log('\n3. unknown format (.AppImage) alongside the known ones')
{
  const body = JSON.stringify([
    release({
      assets: [
        asset('notesAI-1.1.0.dmg', 90),
        asset('notesAI-1.1.0_amd64.AppImage', 70),
        asset('SHA256SUMS.txt', 0),
      ],
    }),
  ])
  const { rows } = await render(async () => ({ ok: true, json: async () => JSON.parse(body) }))
  console.log('   ', rows.map((r) => r.label).join(', '))
  check('the AppImage appears, by filename', rows.some((r) => r.label.endsWith('.AppImage')))
  const checksumRows = rows.filter((r) => r.label.startsWith('Checksums'))
  check('checksums stay separate', checksumRows.length === 1)
  check(
    'and are not mistaken for an installer',
    rows.filter((r) => r.href.endsWith('SHA256SUMS.txt')).length === 1,
  )
}

// 4. API unreachable: never a dead end.
console.log('\n4. API unreachable')
{
  const { rows } = await render(async () => ({ ok: false, status: 503 }))
  check('falls back to the releases page', rows[0].label.includes('releases page'), rows[0].href)
}

console.log(failures === 0 ? '\nOK: all four scenarios behave' : `\n${failures} FAILED`)
process.exit(failures === 0 ? 0 : 1)
