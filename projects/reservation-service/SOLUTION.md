A debug task: double bookings, phantom conflicts and a lossy data repair.

1. **Back-to-back stays were refused.** Dates are *half-open*: a stay is `[checkIn, checkOut)`, so a guest leaving on the 5th and one arriving on the 5th don't clash. Two stays overlap exactly when `aIn < bOut && bIn < aOut`; one shared `overlaps` helper is used everywhere.
2. **Zero-night stays were accepted.** `checkOut` must be strictly after `checkIn`.
3. **Cancelled rooms still looked booked.** The room index kept its own copies of reservations, and cancelling only updated `byId`. The index now stores **ids** and always reads the status from `byId`: a single source of truth.
4. **Double booking under concurrency.** "Is it free? Then insert" is two steps. All state access is `synchronized`, so the check and the insert are atomic.
5. **Data repair.** It now removes duplicate rows by id (keeping the earliest `seq`), settles clashes in *booking order* (`seq`, first come first served) instead of alphabetically by guest, and keeps clashing rows as CANCELLED instead of silently dropping them, as ops asked.
