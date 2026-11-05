A build task. The design: a flat map of comments plus a parent → children index, guarded by one lock.

- **Storage.** `byId` holds every comment; `children` maps a parent id (or a per-post root key) to its child ids *in creation order*. A thread is rebuilt recursively from the root key, so replies always appear in the order they were written.
- **Depth limit.** Each comment stores its `depth`; replying to a comment at `MAX_DEPTH` is rejected, so a reply chain never goes deeper than 3.
- **Soft delete.** Deleting replaces the comment with a copy marked `deleted`, author cleared and text `"[deleted]"`. Its replies keep their place in the thread, which is how real comment systems behave. Only the author may delete; deleting twice is a no-op; replying to a deleted comment is refused. `countVisible` skips deleted ones.
- **Snapshot-safe reads.** `getThread` returns `List.copyOf(...)` at every level, so a caller holding a thread never sees later changes or a `ConcurrentModificationException`.
- **Thread safety.** Every public method is `synchronized`: checks like "the parent exists, isn't deleted and the depth allows a reply" and the insert happen as one step, and the sequence counter never hands out the same id twice.
- **Validation.** A blank author or post id, and blank or too-long text (after trimming), throw `IllegalArgumentException`.
