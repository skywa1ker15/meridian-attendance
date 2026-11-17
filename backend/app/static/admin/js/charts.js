// Hand-drawn SVG charts (line, bars, ring). No dependencies.

const SVG_NS = 'http://www.w3.org/2000/svg';
const PALETTE = ['#2F6FED', '#1FA463', '#F28C28', '#7FA6F5', '#E5484D', '#9FB0CC'];

function svgEl(tag, attrs = {}, text) {
  const n = document.createElementNS(SVG_NS, tag);
  Object.entries(attrs).forEach(([k, v]) => { if (v !== undefined && v !== null) n.setAttribute(k, String(v)); });
  if (text !== undefined) n.textContent = text;
  return n;
}

function niceMax(v) {
  if (v <= 0) return 1;
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  const f = v / p;
  const n = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
  return n * p;
}

/**
 * Line chart. series: [{ name, values: number[], color? }], labels: string[].
 */
export function lineChart({ series, labels, width = 720, height = 240, yLabel = '' }) {
  const pad = { l: 40, r: 16, t: 14, b: 30 };
  const w = width - pad.l - pad.r;
  const h = height - pad.t - pad.b;
  const svg = svgEl('svg', { viewBox: `0 0 ${width} ${height}`, class: 'chart', role: 'img', 'aria-label': `Line chart: ${series.map((s) => s.name).join(', ')}` });
  const maxVal = niceMax(Math.max(1, ...series.flatMap((s) => s.values)));
  const n = Math.max(1, labels.length);
  const x = (i) => pad.l + (n === 1 ? w / 2 : (i * w) / (n - 1));
  const y = (v) => pad.t + h - (v / maxVal) * h;
  // grid
  for (let g = 0; g <= 4; g += 1) {
    const v = (maxVal / 4) * g;
    svg.appendChild(svgEl('line', { x1: pad.l, x2: width - pad.r, y1: y(v), y2: y(v), stroke: 'rgba(255,255,255,0.08)' }));
    svg.appendChild(svgEl('text', { x: pad.l - 6, y: y(v) + 4, 'text-anchor': 'end' }, String(Math.round(v))));
  }
  // x labels (thin out if many)
  const step = Math.ceil(n / 7);
  labels.forEach((lab, i) => {
    if (i % step !== 0 && i !== n - 1) return;
    svg.appendChild(svgEl('text', { x: x(i), y: height - 8, 'text-anchor': 'middle' }, lab));
  });
  series.forEach((s, si) => {
    const color = s.color || PALETTE[si % PALETTE.length];
    if (!s.values.length) return;
    const pts = s.values.map((v, i) => `${x(i)},${y(v)}`);
    const path = `M${pts.join(' L')}`;
    const area = `${path} L${x(s.values.length - 1)},${y(0)} L${x(0)},${y(0)} Z`;
    svg.appendChild(svgEl('path', { d: area, fill: color, opacity: 0.08 }));
    svg.appendChild(svgEl('path', { d: path, fill: 'none', stroke: color, 'stroke-width': 2.2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' }));
    s.values.forEach((v, i) => {
      const c = svgEl('circle', { cx: x(i), cy: y(v), r: 3, fill: color });
      c.appendChild(svgEl('title', {}, `${s.name} — ${labels[i]}: ${v}`));
      svg.appendChild(c);
    });
  });
  if (yLabel) svg.appendChild(svgEl('text', { x: pad.l, y: 10 }, yLabel));
  return svg;
}

/** Horizontal bar list is done in HTML (see overview); this is a vertical bar chart. */
export function barChart({ categories, values, width = 720, height = 220, color = PALETTE[0], valueFormatter = (v) => String(v) }) {
  const pad = { l: 40, r: 10, t: 14, b: 34 };
  const w = width - pad.l - pad.r;
  const h = height - pad.t - pad.b;
  const svg = svgEl('svg', { viewBox: `0 0 ${width} ${height}`, class: 'chart', role: 'img', 'aria-label': 'Bar chart' });
  const maxVal = niceMax(Math.max(1, ...values));
  const n = Math.max(1, categories.length);
  const slot = w / n;
  const bw = Math.min(42, slot * 0.6);
  for (let g = 0; g <= 4; g += 1) {
    const v = (maxVal / 4) * g;
    const yy = pad.t + h - (v / maxVal) * h;
    svg.appendChild(svgEl('line', { x1: pad.l, x2: width - pad.r, y1: yy, y2: yy, stroke: 'rgba(255,255,255,0.08)' }));
    svg.appendChild(svgEl('text', { x: pad.l - 6, y: yy + 4, 'text-anchor': 'end' }, String(Math.round(v))));
  }
  categories.forEach((c, i) => {
    const v = values[i] || 0;
    const bh = (v / maxVal) * h;
    const x = pad.l + slot * i + (slot - bw) / 2;
    const r = svgEl('rect', { x, y: pad.t + h - bh, width: bw, height: bh, rx: 6, fill: color, opacity: 0.9 });
    r.appendChild(svgEl('title', {}, `${c}: ${valueFormatter(v)}`));
    svg.appendChild(r);
    const label = String(c).length > 9 ? String(c).slice(0, 8) + '…' : String(c);
    svg.appendChild(svgEl('text', { x: x + bw / 2, y: height - 10, 'text-anchor': 'middle' }, label));
  });
  return svg;
}

/** Ring / donut progress. value in [0, max]. */
export function ringChart({ value, max, label, sublabel, size = 140, color = PALETTE[1] }) {
  const r = size / 2 - 10;
  const c = 2 * Math.PI * r;
  const pct = max > 0 ? Math.min(1, Math.max(0, value / max)) : 0;
  const svg = svgEl('svg', { viewBox: `0 0 ${size} ${size}`, width: size, height: size, role: 'img', 'aria-label': `${label}: ${Math.round(pct * 100)}%` });
  svg.appendChild(svgEl('circle', { cx: size / 2, cy: size / 2, r, fill: 'none', stroke: 'rgba(255,255,255,0.1)', 'stroke-width': 10 }));
  svg.appendChild(svgEl('circle', { cx: size / 2, cy: size / 2, r, fill: 'none', stroke: color, 'stroke-width': 10, 'stroke-linecap': 'round', 'stroke-dasharray': `${c * pct} ${c}`, transform: `rotate(-90 ${size / 2} ${size / 2})` }));
  const t1 = svgEl('text', { x: size / 2, y: size / 2 - 2, 'text-anchor': 'middle', style: 'font-size:22px;font-weight:700;fill:#E8EEF9' }, label);
  const t2 = svgEl('text', { x: size / 2, y: size / 2 + 18, 'text-anchor': 'middle' }, sublabel || '');
  svg.appendChild(t1); svg.appendChild(t2);
  return svg;
}

export function legend(items) {
  const wrap = document.createElement('div');
  wrap.className = 'legend';
  items.forEach((it, i) => {
    const s = document.createElement('span');
    s.style.setProperty('--c', it.color || PALETTE[i % PALETTE.length]);
    s.textContent = it.name;
    wrap.appendChild(s);
  });
  return wrap;
}

export { PALETTE };
