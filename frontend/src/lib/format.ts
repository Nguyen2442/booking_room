const ACRONYMS = new Set(['tv', 'vc', 'av', 'led', 'usb', 'hdmi']);

/** "VIDEO_CONFERENCE" -> "Video conference", "TV" -> "TV". */
export function prettyEquipment(e: string): string {
  const words = e.replace(/_/g, ' ').toLowerCase().split(' ').filter(Boolean);
  return words
    .map((w, i) => (ACRONYMS.has(w) ? w.toUpperCase() : i === 0 ? w[0].toUpperCase() + w.slice(1) : w))
    .join(' ');
}
