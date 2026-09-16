import { ConversionError } from './errors.js';

/** Shared by HTTP transfers and Queue workers within one Node.js process. */
export function createAdmission() {
  let busy = false;
  return { acquire() {
    if (busy) throw new ConversionError(503, 'CONVERSION_BUSY', 'Another conversion is running; retry later.');
    busy = true;
    let released = false;
    return () => { if (!released) { released = true; busy = false; } };
  } };
}
