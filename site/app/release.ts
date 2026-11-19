export type Asset = { name: string; size: number; browser_download_url: string };
export type Release = {
  tag_name: string;
  html_url: string;
  published_at: string | null;
  draft: boolean;
  prerelease: boolean;
  assets: Asset[];
};

export type Apk = { abi: string; url: string; size: number };
export type Downloads = {
  tag: string;
  notesUrl: string;
  publishedAt: string | null;
  prerelease: boolean;
  universal: Apk;
  perAbi: Apk[];
  checksumsUrl: string | null;
};

const ABI = /-(universal|arm64-v8a|armeabi-v7a|x86_64|x86)(?:-[a-z]+)?\.apk$/;
const CHECKSUMS = /^(SHA256SUMS(\.txt)?|checksums\.txt|sha256sums\.txt)$/i;

export function pickDownloads(releases: Release[]): Downloads {
  const release = releases.find((r) => !r.draft);
  if (!release) throw new Error("No published release found");
  const apks = release.assets.filter((a) => a.name.endsWith(".apk"));
  const split = apks.flatMap((a): Apk[] => {
    const abi = ABI.exec(a.name)?.[1];
    return abi ? [{ abi, url: a.browser_download_url, size: a.size }] : [];
  });
  const universal =
    split.find((a) => a.abi === "universal") ??
    (apks.length === 1 ? { abi: "universal", url: apks[0].browser_download_url, size: apks[0].size } : undefined);
  if (!universal) throw new Error(`${release.tag_name} has no universal APK`);
  return {
    tag: release.tag_name,
    notesUrl: release.html_url,
    publishedAt: release.published_at,
    prerelease: release.prerelease,
    universal,
    perAbi: split.filter((a) => a.abi !== "universal"),
    checksumsUrl: release.assets.find((a) => CHECKSUMS.test(a.name))?.browser_download_url ?? null,
  };
}

export async function latestDownloads(repo: string): Promise<Downloads> {
  const headers: Record<string, string> = {
    Accept: "application/vnd.github+json",
    "User-Agent": "android-site-build",
  };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  const res = await fetch(`https://api.github.com/repos/${repo}/releases?per_page=10`, { headers });
  if (!res.ok) throw new Error(`GitHub releases request failed: ${res.status}`);
  return pickDownloads((await res.json()) as Release[]);
}
