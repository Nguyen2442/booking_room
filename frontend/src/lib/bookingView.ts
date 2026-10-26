import type { Booking } from '../api/types';
import type { Key } from './messages';

/** The label to show for a booking: a masked (private) booking has no real title for this viewer. */
export function titleOf(b: Booking, t: (k: Key) => string): string {
  return b.masked ? t('priv.label') : b.title;
}
