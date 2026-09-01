/**
 * Group invite codes: the only credential for joining a group, so they are minted from a
 * CSPRNG rather than from a counter or a timestamp.
 */

import { randomInt } from 'node:crypto';

/**
 * No 0/O, no 1/I/L: the code is read off one phone screen and typed into another, and a
 * character pair nobody can tell apart turns every mistyped join into a support question.
 * 30 symbols over 8 places is ~39 bits, which is far past guessing a live group by hand.
 */
const ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

export const INVITE_CODE_LENGTH = 8;

export function newInviteCode(): string {
  let code = '';
  for (let index = 0; index < INVITE_CODE_LENGTH; index += 1) {
    code += ALPHABET.charAt(randomInt(ALPHABET.length));
  }
  return code;
}

/**
 * What a user typed -> what is looked up. Case and separators are noise: a phone keyboard
 * capitalises on its own and people write the code back in groups of four.
 *
 * Nothing is substituted. The confusable characters are absent from ALPHABET rather than
 * folded together, so a code containing one is a genuine mistype and deserves the 404 —
 * mapping O onto Q would only join somebody to the wrong group.
 */
export function normalizeInviteCode(value: string): string {
  return value.toUpperCase().replace(/[\s-]+/g, '');
}
