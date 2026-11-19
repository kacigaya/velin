import { site, type Part } from "./config";
import { Demo } from "./demo";
import { latestDownloads, type Apk } from "./release";

const base = process.env.BASE_PATH;
const repoUrl = `https://github.com/${site.repo}`;

function resolve(href: string) {
  if (href.startsWith("file:")) return `${repoUrl}/blob/HEAD/${href.slice(5)}`;
  if (href.startsWith("repo:")) return href.length > 5 ? `${repoUrl}/${href.slice(5)}` : repoUrl;
  return href;
}

function Text({ parts }: { parts: Part[] }) {
  return parts.map((p, i) =>
    typeof p === "string" ? (
      p
    ) : (
      <a key={i} href={resolve(p.href)}>
        {p.text}
      </a>
    ),
  );
}

const ABI_LABELS: Record<string, string> = {
  "arm64-v8a": "Most phones and tablets",
  "armeabi-v7a": "Older 32-bit phones",
  x86_64: "Emulators and some Chromebooks",
  x86: "Older emulators",
};

function Size({ bytes }: { bytes: number }) {
  return (
    <span className="amount">
      <b>{(bytes / 1e6).toFixed(1)}</b> MB
    </span>
  );
}

function DownloadIcon() {
  return (
    <svg aria-hidden="true" viewBox="0 -960 960 960" width="24" height="24" fill="currentColor">
      <path d="M480-337q-8 0-15-2.5t-13-8.5L308-492q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T365-549l75 75v-286q0-17 11.5-28.5T480-800q17 0 28.5 11.5T520-760v286l75-75q12-12 28.5-11.5T652-548q11 12 11.5 28T652-492L508-348q-6 6-13 8.5t-15 2.5ZM240-160q-33 0-56.5-23.5T160-240v-80q0-17 11.5-28.5T200-360q17 0 28.5 11.5T240-320v80h480v-80q0-17 11.5-28.5T760-360q17 0 28.5 11.5T800-320v80q0 33-23.5 56.5T720-160H240Z" />
    </svg>
  );
}

function AbiRow({ apk }: { apk: Apk }) {
  return (
    <li>
      <a href={apk.url}>{apk.abi}</a>
      <span>{ABI_LABELS[apk.abi] ?? ""}</span>
      <Size bytes={apk.size} />
    </li>
  );
}

export default async function Home() {
  const d = await latestDownloads(site.repo);
  const version = d.tag.replace(/^v/, "");
  const date = d.publishedAt
    ? new Intl.DateTimeFormat("en-GB", { dateStyle: "medium", timeZone: "UTC" }).format(new Date(d.publishedAt))
    : null;

  return (
    <>
      <header className="bar">
        <a className="brand" href={`${base}/`}>
          <img src={`${base}/logo.svg`} alt="" width={40} height={40} />
          {site.name}
        </a>
        <a href={repoUrl}>Source code</a>
      </header>

      <main>
        <section className={site.demo ? "hero" : "hero hero-solo"} aria-labelledby="title">
          <div className="hero-text">
            <h1 id="title">{site.headline}</h1>
            <p className="lead">{site.lead}</p>

            <div className="download" id="download">
              <p className="release">
                <b>
                  {site.name} {version}
                </b>
                <span>
                  {d.prerelease ? "Prerelease" : "Release"}
                  {date && <> · {date}</>}
                </span>
              </p>
              <a className="button" href={d.universal.url}>
                <DownloadIcon />
                Download APK
              </a>
              <p className="meta">
                <Size bytes={d.universal.size} /> · {site.minAndroid} · Works on all devices
              </p>
              {d.universal.url.includes("debug") && (
                <p className="note">
                  This build uses a debug signing key. A later version can need a reinstall, which deletes the app
                  data.
                </p>
              )}
              {d.perAbi.length > 0 && (
                <details>
                  <summary>Smaller APK for your CPU type</summary>
                  <ul className="abis">
                    {d.perAbi.map((apk) => (
                      <AbiRow key={apk.abi} apk={apk} />
                    ))}
                  </ul>
                </details>
              )}
              <p className="links">
                <a href={d.notesUrl}>Release notes</a>
                {d.checksumsUrl && <a href={d.checksumsUrl}>SHA-256 checksums</a>}
              </p>
            </div>
          </div>

          {site.demo && (
            <Demo
              src={`${base}/${site.demo.video}`}
              poster={`${base}/${site.demo.poster}`}
              width={site.demo.width}
              height={site.demo.height}
              caption={site.demo.caption}
            />
          )}
        </section>

        <section className="features" aria-labelledby="features-title">
          <h2 id="features-title">{site.features.title}</h2>
          <dl>
            {site.features.items.map((f) => (
              <div key={f.name}>
                <dt>{f.name}</dt>
                <dd>{f.text}</dd>
              </div>
            ))}
          </dl>
          {site.features.aside && (
            <p className="aside">
              <Text parts={site.features.aside} />
            </p>
          )}
        </section>

        {site.privacy && (
          <section className="privacy" aria-labelledby="privacy-title">
            <div className="privacy-inner">
              <h2 id="privacy-title">{site.privacy.title}</h2>
              <p>{site.privacy.intro}</p>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Service</th>
                      <th scope="col">Use</th>
                      <th scope="col">Data sent</th>
                    </tr>
                  </thead>
                  <tbody>
                    {site.privacy.services.map((s) => (
                      <tr key={s.name}>
                        <th scope="row">
                          <a href={s.href}>{s.name}</a>
                        </th>
                        <td data-label="Use">{s.use}</td>
                        <td data-label="Data sent">{s.sent}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p>
                <Text parts={site.privacy.outro} />
              </p>
            </div>
          </section>
        )}

        <section className="install" aria-labelledby="install-title">
          <h2 id="install-title">Install</h2>
          <ol>
            <li>
              <span>
                On your Android device, select <a href="#download">Download APK</a>.
              </span>
            </li>
            {site.install.map((step) => (
              <li key={step}>
                <span>{step}</span>
              </li>
            ))}
          </ol>
        </section>
      </main>

      <footer>
        <p>
          <Text parts={site.footer.license} />
        </p>
        <p className="links">
          {site.footer.links.map((l) => (
            <a key={l.text} href={resolve(l.href)}>
              {l.text}
            </a>
          ))}
        </p>
        {site.footer.credits.map((line, i) => (
          <p key={i} className="credits">
            <Text parts={line} />
          </p>
        ))}
      </footer>
    </>
  );
}
