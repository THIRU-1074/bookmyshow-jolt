import { useState, type ReactElement } from 'react';
import { posterSpec, rng, usablePosterUrl, type PosterSpec } from '../lib/art';

interface Props {
  title: string;
  posterUrl?: string | null;
  /** Small line under the title, e.g. the certificate. */
  caption?: string;
  className?: string;
  /** Load eagerly: for the hero poster, which is the largest paint. */
  eager?: boolean;
}

/**
 * A film poster. Uses the movie's https poster when it has one, otherwise generated typographic
 * art from the title: the same title always gets the same composition.
 */
export function Poster({ title, posterUrl, caption, className, eager }: Props) {
  const url = usablePosterUrl(posterUrl);
  const [failed, setFailed] = useState(false);
  return (
    <div className={`poster ${className ?? ''}`}>
      {url && !failed ? (
        <img
          src={url}
          alt=""
          loading={eager ? 'eager' : 'lazy'}
          decoding="async"
          onError={() => setFailed(true)}
          width={400}
          height={600}
        />
      ) : (
        <GeneratedPoster title={title} caption={caption} />
      )}
    </div>
  );
}

const W = 400;
const H = 600;

function motif(spec: PosterSpec, id: string): ReactElement {
  const { palette: p, seed } = spec;
  const r = rng(seed);
  switch (spec.motif) {
    case 'sun': {
      const cy = 170 + r() * 80;
      return (
        <g>
          <circle cx={W / 2} cy={cy} r={120 + r() * 40} fill={`url(#${id}-glow)`} />
          {Array.from({ length: 7 }, (_, i) => (
            <rect key={i} x="0" y={cy + 20 + i * 18} width={W} height={6 + i} fill={p.deep} />
          ))}
        </g>
      );
    }
    case 'beams': {
      const ox = 60 + r() * 280;
      return (
        <g opacity="0.85">
          {Array.from({ length: 5 }, (_, i) => {
            const spread = 50 + i * 70;
            return (
              <polygon
                key={i}
                points={`${ox},-10 ${ox - spread},${H} ${ox - spread + 26},${H}`}
                fill={p.glow}
                opacity={0.12 + i * 0.05}
              />
            );
          })}
          <circle cx={ox} cy={-10} r="26" fill={p.glow} />
        </g>
      );
    }
    case 'rings': {
      const cx = 120 + r() * 160;
      const cy = 150 + r() * 120;
      return (
        <g fill="none" stroke={p.glow}>
          {Array.from({ length: 9 }, (_, i) => (
            <circle key={i} cx={cx} cy={cy} r={24 + i * 26} strokeWidth={i % 3 === 0 ? 3 : 1} opacity={1 - i * 0.09} />
          ))}
        </g>
      );
    }
    case 'horizon': {
      const hy = 250 + r() * 60;
      return (
        <g>
          <rect x="0" y={hy} width={W} height={H - hy} fill={p.deep} />
          {Array.from({ length: 12 }, (_, i) => (
            <line key={`h${i}`} x1="0" x2={W} y1={hy + i * i * 3} y2={hy + i * i * 3} stroke={p.glow} strokeWidth="1" opacity="0.5" />
          ))}
          {Array.from({ length: 11 }, (_, i) => (
            <line key={`v${i}`} x1={W / 2} y1={hy} x2={-200 + i * 80} y2={H} stroke={p.glow} strokeWidth="1" opacity="0.4" />
          ))}
          <circle cx={W / 2} cy={hy} r="70" fill={p.glow} opacity="0.9" />
          <rect x="0" y={hy} width={W} height="80" fill={`url(#${id}-fade)`} />
        </g>
      );
    }
    case 'stack':
    default: {
      // A strip of film crossing the frame, sprocket holes and all.
      const angle = -18 + r() * 36;
      const y = 140 + r() * 120;
      return (
        <g transform={`rotate(${angle.toFixed(1)} ${W / 2} ${y})`}>
          <rect x="-120" y={y - 60} width={W + 240} height="120" fill={p.glow} opacity="0.9" />
          {Array.from({ length: 22 }, (_, i) => (
            <g key={i} fill={p.deep}>
              <rect x={-110 + i * 30} y={y - 52} width="16" height="12" rx="3" />
              <rect x={-110 + i * 30} y={y + 40} width="16" height="12" rx="3" />
            </g>
          ))}
          {Array.from({ length: 6 }, (_, i) => (
            <rect key={`f${i}`} x={-100 + i * 110} y={y - 32} width="96" height="64" rx="4" fill={p.sky} opacity={0.55 + (i % 2) * 0.2} />
          ))}
        </g>
      );
    }
  }
}

export function GeneratedPoster({ title, caption }: { title: string; caption?: string }) {
  const spec = posterSpec(title);
  const { palette: p, lines } = spec;
  const id = `pg${spec.seed.toString(36)}`;
  // Big Shoulders runs about half an em per glyph; size the longest line to the frame width.
  const longest = Math.max(...lines.map((l) => l.length));
  const size = Math.min(lines.length > 3 ? 62 : lines.length === 3 ? 72 : 84, Math.floor((W - 60) / (longest * 0.5)));
  const lineH = size * 0.92;
  const baseY = H - 70 - (lines.length - 1) * lineH;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Poster art for ${title}`} preserveAspectRatio="xMidYMid slice">
      <defs>
        <linearGradient id={`${id}-bg`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={p.sky} />
          <stop offset="1" stopColor={p.deep} />
        </linearGradient>
        <radialGradient id={`${id}-glow`}>
          <stop offset="0" stopColor={p.glow} />
          <stop offset="0.7" stopColor={p.glow} stopOpacity="0.6" />
          <stop offset="1" stopColor={p.glow} stopOpacity="0" />
        </radialGradient>
        <linearGradient id={`${id}-fade`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={p.deep} stopOpacity="0" />
          <stop offset="1" stopColor={p.deep} />
        </linearGradient>
        <linearGradient id={`${id}-foot`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={p.deep} stopOpacity="0" />
          <stop offset="0.55" stopColor={p.deep} stopOpacity="0.92" />
        </linearGradient>
      </defs>
      <rect width={W} height={H} fill={`url(#${id}-bg)`} />
      {motif(spec, id)}
      <rect x="0" y={baseY - lineH - 40} width={W} height={H - baseY + lineH + 40} fill={`url(#${id}-foot)`} />
      <g fill={p.ink} fontFamily="'Big Shoulders Display Variable', 'Arial Narrow', sans-serif" fontWeight="800">
        {lines.map((l, i) => (
          <text key={i} x="28" y={baseY + i * lineH} fontSize={size} letterSpacing="1">
            {l}
          </text>
        ))}
      </g>
      {caption && (
        <text x="30" y={H - 30} fill={p.glow} fontSize="20" fontFamily="'Atkinson Hyperlegible', sans-serif" fontWeight="700">
          {caption}
        </text>
      )}
    </svg>
  );
}
