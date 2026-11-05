A debug task: access control, return values, persistence and crash safety.

1. **Anyone could read any list.** `getList` now goes through `owned(requester, listId)`, the same ownership check the write methods use.
2. **Callers could change a list.** `getList` returned the internal `movies` list itself. Every read returns an unmodifiable snapshot (`List.copyOf`).
3. **Wrong return values.** Adding a movie that's already there returns `false` (no duplicate), and `removeMovie` returns whether it actually removed something.
4. **Deleted lists came back.** `deleteList` never saved, so the list reappeared after a restart. It saves now.
5. **Duplicate ids after a restart.** The id counter restarted at 1, so new lists overwrote old ones. Loading sets `nextId` past the highest id on disk.
6. **Half-written files.** Saving wrote straight over the data file, so a crash mid-write left it corrupt. It now writes a temp file in the same folder and swaps it in with `ATOMIC_MOVE`: readers see the old file or the new one, never a mix.
7. **Memory and disk agree.** If a save fails, the in-memory change is undone before the error is thrown.
8. **File format safety.** Owners containing the `|` separator or a newline are rejected, and a corrupt line fails loudly on load. Lists come back sorted by their numeric id (so W10 comes after W9).
