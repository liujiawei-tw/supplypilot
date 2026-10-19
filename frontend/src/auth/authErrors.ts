export class PostLoginVerificationError extends Error {
  readonly originalError: unknown;

  constructor(originalError: unknown) {
    super('Login succeeded but the session could not be verified.');
    this.name = 'PostLoginVerificationError';
    this.originalError = originalError;
  }
}
