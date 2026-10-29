/** Public conversion errors contain stable codes and safe, bounded descriptions. */
export class ConversionError extends Error {
  constructor(code, message, status = 422, details = undefined) {
    super(message);
    this.name = 'ConversionError';
    this.code = code;
    this.status = status;
    this.statusCode = status;
    if (details !== undefined) this.details = details;
  }
}
