import type { Metadata, Viewport } from "next";
import localFont from "next/font/local";
import { site } from "./config";
import "./globals.css";

const sans = localFont({
  src: "./fonts/google-sans-flex.woff2",
  weight: "300 800",
  display: "swap",
  variable: "--font-sans",
});

export const metadata: Metadata = {
  title: site.title,
  description: site.description,
  icons: { icon: `${process.env.BASE_PATH}/logo.svg` },
  openGraph: { title: site.name, description: site.description, type: "website" },
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: site.themeColor.light },
    { media: "(prefers-color-scheme: dark)", color: site.themeColor.dark },
  ],
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={sans.variable}>
      <body>{children}</body>
    </html>
  );
}
