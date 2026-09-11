package io.jcordis.loader;

/**
 * One tree-side mutation, reported to {@link EntryTree#commit(EntryChange)}
 * synchronously after it has been applied to the tree. Mirrors Cordis's
 * {@code EntryChange}.
 *
 * <ul>
 *   <li>{@link #options} is {@code null}: the entry was removed from
 *       {@link #group}.
 *   <li>{@link #legacy} is {@code null}: the entry was created in
 *       {@link #group}.
 *   <li>both present: the entry was updated and now lives in {@link #group};
 *       {@link #from} is the group it left when the update moved it.
 * </ul>
 *
 * <p>Listeners receive the change after {@code entry.options} already holds the
 * new state, so a persistence layer (such as {@code Include}) can write the file
 * back without another round trip through the tree.
 */
public final class EntryChange {

    public final String id;
    public final EntryGroup group;
    /** The group the entry left, or {@code null} when it did not move. */
    public final EntryGroup from;
    /** The options after the change; {@code null} when the entry was removed. */
    public final EntryOptions options;
    /** The options before the change; {@code null} when the entry was created. */
    public final EntryOptions legacy;

    private EntryChange(String id, EntryGroup group, EntryGroup from, EntryOptions options, EntryOptions legacy) {
        this.id = id;
        this.group = group;
        this.from = from;
        this.options = options;
        this.legacy = legacy;
    }

    /** The entry was created in {@code group}. */
    public static EntryChange created(String id, EntryGroup group, EntryOptions options) {
        return new EntryChange(id, group, null, options, null);
    }

    /** The entry was removed from {@code group}. */
    public static EntryChange removed(String id, EntryGroup group, EntryOptions legacy) {
        return new EntryChange(id, group, null, null, legacy);
    }

    /** The entry was updated in place, or moved from {@code from} into {@code group}. */
    public static EntryChange updated(
            String id, EntryGroup group, EntryGroup from, EntryOptions options, EntryOptions legacy) {
        return new EntryChange(id, group, from, options, legacy);
    }

    public boolean isCreated() {
        return legacy == null;
    }

    public boolean isRemoved() {
        return options == null;
    }

    public boolean isMoved() {
        return from != null;
    }

    @Override
    public String toString() {
        return "EntryChange[" + (isRemoved() ? "remove " : isCreated() ? "create " : "update ") + id + " in group "
                + (group == null ? "?" : String.valueOf(group.hashCode()))
                + (isMoved() ? " from " + from.hashCode() : "") + "]";
    }
}
