export class ConversionError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = 'ConversionError';
    this.status = status;
    this.code = code;
  }
}

export function throwIfAborted(signal) {
  if (signal?.aborted) throw signal.reason;
}
