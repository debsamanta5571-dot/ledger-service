// Readable random passwords for new sign-in accounts. No look-alike characters (0/O, 1/l/I), so a password can be
// read out or typed without mistakes. 20 characters from 55 symbols is about 115 bits: far beyond the identity
// service's 12-character minimum and any guessing attack.
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789';

export function generatePassword(length = 20, cryptoImpl = globalThis.crypto) {
  const out = [];
  // Rejection sampling: only bytes below the largest multiple of the alphabet size, so every character is equally
  // likely (a plain "byte % 55" would favour the first characters).
  const limit = 256 - (256 % ALPHABET.length);
  while (out.length < length) {
    for (const b of cryptoImpl.getRandomValues(new Uint8Array(length * 2))) {
      if (b < limit && out.length < length) out.push(ALPHABET[b % ALPHABET.length]);
    }
  }
  return out.join('');
}
