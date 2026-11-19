export type Link = { text: string; href: string };

export type Part = string | Link;

export type SiteConfig = {
  name: string;
  repo: string;
  title: string;
  description: string;
  headline: string;
  lead: string;
  minAndroid: string;
  themeColor: { light: string; dark: string };
  demo: { video: string; poster: string; width: number; height: number; caption: string } | null;
  features: { title: string; items: { name: string; text: string }[]; aside: Part[] | null };
  privacy: {
    title: string;
    intro: string;
    services: { name: string; href: string; use: string; sent: string }[];
    outro: Part[];
  } | null;
  install: string[];
  footer: { license: Part[]; links: Link[]; credits: Part[][] };
};

export const site: SiteConfig = {
  name: "Velin",
  repo: "kacigaya/velin",
  title: "Velin: a mail client for Tuta on Android",
  description:
    "A native, open-source Android mail client for Tuta. End-to-end encryption from the Tuta Rust SDK. No analytics, no crash telemetry.",
  headline: "A native Android mail client for Tuta.",
  lead: "Read, sort, and answer your Tuta mail in a native Android app. Open source, end-to-end encrypted, no analytics, no crash telemetry.",
  minAndroid: "Android 8.0 or later",
  themeColor: { light: "#faf8ff", dark: "#121318" },
  demo: {
    video: "demo.mp4",
    poster: "demo-poster.webp",
    width: 1280,
    height: 720,
    caption: "The inbox, blocked remote images, the encrypted composer, and two panes on a tablet.",
  },
  features: {
    title: "What it does",
    items: [
      {
        name: "Tuta encryption",
        text: "Velin keeps the Tuta encryption model without changes. The cryptography comes from the Tuta Rust SDK, compiled to native code. Two-factor sign-in with TOTP works.",
      },
      {
        name: "Safe HTML mail",
        text: "Velin removes scripts, unsafe tags, and external CSS. Remote images and tracking pixels stay blocked until you allow them for that mail.",
      },
      {
        name: "Sort your mail",
        text: "Mark mail as read or unread. Archive, move, delete, and label it. Mail stays in date order, and unread mail stands out.",
      },
      {
        name: "Write and reply",
        text: "Save drafts. Reply, forward, and send confidential mail to other Tuta users. Mail to other providers is not available yet.",
      },
      {
        name: "Offline mail",
        text: "Saved mail stays on your device, encrypted with an Android Keystore key. Velin updates it when you connect again.",
      },
      {
        name: "Any screen",
        text: "Phones, tablets, and foldables, with a list and a mail pane side by side on large screens. Light and dark themes, with Material You colors on Android 12 and later.",
      },
    ],
    aside: [
      "Velin is alpha software. It has no new-mail notifications yet. Do not use it as your main mail app yet. See the ",
      { text: "roadmap", href: "file:docs/ROADMAP.md" },
      " for what works now.",
    ],
  },
  privacy: null,
  install: [
    "Open the file. If Android asks, allow installs from your browser or file manager.",
    "Open Velin and sign in with your Tuta address and password. If your account uses two-factor authentication, enter the code from your authenticator app.",
  ],
  footer: {
    license: ["Velin is free software under the ", { text: "GNU General Public License, version 3", href: "file:LICENSE" }, "."],
    links: [
      { text: "Source code", href: "repo:" },
      { text: "Roadmap", href: "file:docs/ROADMAP.md" },
      { text: "All releases", href: "repo:releases" },
    ],
    credits: [
      ["Velin is an independent project. It is not affiliated with, endorsed by, or connected to Tuta GmbH."],
      [
        "Typeface: Google Sans Flex, ",
        { text: "SIL Open Font License 1.1", href: "file:site/app/fonts/OFL.txt" },
        ".",
      ],
    ],
  },
};
