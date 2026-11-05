A debug task with security bugs mixed in with config and time bugs. Each fix maps to a failing test.

**Config and time**
1. **TTL ignored.** The code read `reset.ttl.minute` (typo), so the default always won. It reads `reset.ttl.minutes`, and bad values (non-numbers, ≤ 0, code length outside 4..10, unknown time zone) fail at startup.
2. **Wrong expiry.** Expiry used `LocalDateTime.now()` interpreted as UTC: wrong on any server not running in UTC, and impossible to test. It's now `clock.instant().plus(ttl)`, using the injected `Clock`.
3. **Expiry boundary.** A code is expired at `now >= expiresAt` (`!isBefore`), not only after it.
4. **Wrong zone in messages.** `describeExpiry` used the server's default zone; it uses the configured one, with an English locale so the output doesn't depend on the machine.
5. **Short codes.** `Integer.toString(42)` gives `"42"` for a 6-digit code. Codes are zero-padded to their full length.

**Security**
6. **Raw passwords stored.** `setPasswordHash` received the plain password; it now gets `PasswordHasher.hash(...)`.
7. **Codes reusable.** A successful reset consumes the code.
8. **Brute force.** After 5 wrong guesses the code is thrown away.
9. **Races.** Check-and-consume runs inside `computeIfPresent`, one atomic step per email, so two concurrent confirmations can't both succeed.
10. **Timing leak.** Codes are compared in constant time (XOR every character), so response time doesn't reveal how many leading digits were right.
11. **Validation order.** A too-short password is rejected first, before any code check consumes an attempt.
