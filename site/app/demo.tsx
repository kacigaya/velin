"use client";

import { useEffect, useRef } from "react";

type Props = { src: string; poster: string; width: number; height: number; caption: string };

export function Demo({ src, poster, width, height, caption }: Props) {
  const video = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    if (!matchMedia("(prefers-reduced-motion: reduce)").matches) video.current?.play().catch(() => {});
  }, []);

  return (
    <figure className="demo">
      <video ref={video} src={src} poster={poster} width={width} height={height} controls muted loop playsInline preload="metadata" />
      <figcaption>{caption}</figcaption>
    </figure>
  );
}
