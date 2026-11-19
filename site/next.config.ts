import type { NextConfig } from "next";

const basePath = process.env.PAGES_BASE_PATH ?? "";

const config: NextConfig = {
  output: "export",
  basePath,
  trailingSlash: true,
  images: { unoptimized: true },
  env: { BASE_PATH: basePath },
};

export default config;
