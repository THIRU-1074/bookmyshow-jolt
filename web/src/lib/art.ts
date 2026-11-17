// Deterministic generative art: poster compositions from a film title and the scannable-looking
// matrix on tickets. Everything is computed locally; no image hosts are involved.

export function hash(text: string): number {
  // FNV-1a, 32 bit.
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

export function rng(seed: number): () => number {
  let s = seed || 0x9e3779b9;
  return () => {
    // xorshift32
    s ^= s << 13;
    s ^= s >>> 17;
    s ^= s << 5;
    return (s >>> 0) / 0x1_0000_0000;
  };
}

export interface PosterPalette {
  name: string;
  sky: string;
  deep: string;
  glow: string;
  ink: string;
}

// Hand-tuned pairs; each ink colour passes 4.5:1 against both sky and deep.
export const PALETTES: PosterPalette[] = [
  { name: 'monsoon', sky: '#1d3b53', deep: '#0b1622', glow: '#7fd1c7', ink: '#f2efe6' },
  { name: 'marigold', sky: '#8a2c0f', deep: '#2b0d06', glow: '#ffb347', ink: '#fff4e0' },
  { name: 'neon', sky: '#3a0f5c', deep: '#120424', glow: '#ff5fa2', ink: '#fdeaff' },
  { name: 'river', sky: '#0f4d4a', deep: '#04201f', glow: '#e9c46a', ink: '#f1fff9' },
  { name: 'dusk', sky: '#5b2a4a', deep: '#1c0b18', glow: '#f4a261', ink: '#fff0e8' },
  { name: 'salt', sky: '#6b5a3a', deep: '#211a0e', glow: '#f5e6c4', ink: '#fffaf0' },
  { name: 'temple', sky: '#2d1f4f', deep: '#0d0820', glow: '#c9a7ff', ink: '#f6f0ff' },
  { name: 'cyclone', sky: '#24423a', deep: '#09140f', glow: '#b8e986', ink: '#f3fff0' },
];

export type Motif = 'sun' | 'beams' | 'rings' | 'horizon' | 'stack';
const MOTIFS: Motif[] = ['sun', 'beams', 'rings', 'horizon', 'stack'];

export interface PosterSpec {
  palette: PosterPalette;
  motif: Motif;
  lines: string[];
  seed: number;
}

/** Breaks a title into poster lines of roughly `width` characters, never splitting words. */
export function titleLines(title: string, width = 11): string[] {
  const words = title.trim().split(/\s+/).filter(Boolean);
  const lines: string[] = [];
  let cur = '';
  for (const w of words) {
    if (!cur) cur = w;
    else if ((cur + ' ' + w).length <= width) cur += ' ' + w;
    else {
      lines.push(cur);
      cur = w;
    }
  }
  if (cur) lines.push(cur);
  return lines.length ? lines : ['Untitled'];
}

export function posterSpec(title: string): PosterSpec {
  const seed = hash(title.toLowerCase());
  return {
    palette: PALETTES[seed % PALETTES.length] as PosterPalette,
    motif: MOTIFS[(seed >>> 8) % MOTIFS.length] as Motif,
    lines: titleLines(title),
    seed,
  };
}

/** Only absolute https posters are fetched; site paths in the demo seed have no files behind them. */
export function usablePosterUrl(url: string | null | undefined): string | null {
  return url && /^https:\/\//i.test(url) ? url : null;
}

/**
 * A size x size module matrix derived from the booking code, with three finder squares so it
 * reads as a ticket code at a glance. It is decorative: the human-readable code is the key.
 */
export function codeMatrix(code: string, size = 25): boolean[][] {
  const next = rng(hash(code));
  const m: boolean[][] = Array.from({ length: size }, () => Array.from({ length: size }, () => next() > 0.52));
  const finder = (r0: number, c0: number) => {
    for (let r = -1; r <= 7; r++) {
      for (let c = -1; c <= 7; c++) {
        const rr = r0 + r;
        const cc = c0 + c;
        if (rr < 0 || cc < 0 || rr >= size || cc >= size) continue;
        const ring = r === 0 || r === 6 || c === 0 || c === 6;
        const core = r >= 2 && r <= 4 && c >= 2 && c <= 4;
        (m[rr] as boolean[])[cc] = r >= 0 && r <= 6 && c >= 0 && c <= 6 && (ring || core);
      }
    }
  };
  finder(0, 0);
  finder(0, size - 7);
  finder(size - 7, 0);
  return m;
}
