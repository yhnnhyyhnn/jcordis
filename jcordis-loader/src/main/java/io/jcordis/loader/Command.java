package io.jcordis.loader;

import java.util.function.Consumer;

/**
 * A tree mutation command (command pattern).
 *
 * <p>Encapsulates a loader tree operation ({@link TreeCommand#create},
 * {@link TreeCommand#update}, {@link TreeCommand#remove}) as a reusable
 * object, decoupling callers from the tree mechanics and enabling queuing,
 * logging or undo of operations.
 */
@FunctionalInterface
public interface Command {

    /** Executes this command against a loader tree. */
    void execute(EntryTree tree);

    /** A command factory producing concrete tree operations. */
    final class TreeCommand {

        private TreeCommand() {}

        /** Creates an entry under {@code parent} (or the root). */
        public static Command create(EntryOptions options, String parent) {
            return tree -> tree.create(options, parent);
        }

        /** Removes the entry with the given id. */
        public static Command remove(String id) {
            return tree -> tree.remove(id);
        }

        /** Updates the entry, optionally moving it to another group. */
        public static Command update(String id, EntryOptions options, String parent) {
            return tree -> tree.update(id, options, parent);
        }

        /** Adapts an arbitrary {@code tree -> void} action into a command. */
        public static Command of(Consumer<EntryTree> action) {
            return action::accept;
        }
    }
}
