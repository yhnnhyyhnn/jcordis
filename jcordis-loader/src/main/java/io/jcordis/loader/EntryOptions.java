package io.jcordis.loader;

import java.util.Map;

/**
 * Configuration options of a loader entry, mirroring Cordis's
 * {@code EntryOptions}.
 *
 * <p>All fields are optional; use the {@link Builder} for a fluent,
 * immutable-by-convention construction path.
 */
public final class EntryOptions {

    public String id;
    public String name;
    public Object config;
    public Boolean group;
    public Boolean disabled;
    public Map<String, Object> inject;
    public Map<String, Object> intercept;
    public Map<String, Object> isolate;

    /** Copies every non-null field from {@code source}. */
    public void merge(EntryOptions source) {
        if (source.id != null) id = source.id;
        if (source.name != null) name = source.name;
        if (source.config != null) config = source.config;
        if (source.group != null) group = source.group;
        if (source.disabled != null) disabled = source.disabled;
        if (source.inject != null) inject = source.inject;
        if (source.intercept != null) intercept = source.intercept;
        if (source.isolate != null) isolate = source.isolate;
    }

    /**
     * Immutable snapshot of this entry's options (memento pattern).
     *
     * <p>Captured before a mutation, a snapshot can {@link #restore} the
     * original state — the rollback mechanism used by config updates.
     */
    public Snapshot snapshot() {
        return new Snapshot(id, name, config, group, disabled, inject, intercept, isolate);
    }

    /** Immutable memento holding the field values at capture time. */
    public static final class Snapshot {
        private final String id;
        private final String name;
        private final Object config;
        private final Boolean group;
        private final Boolean disabled;
        private final Map<String, Object> inject;
        private final Map<String, Object> intercept;
        private final Map<String, Object> isolate;

        private Snapshot(
                String id,
                String name,
                Object config,
                Boolean group,
                Boolean disabled,
                Map<String, Object> inject,
                Map<String, Object> intercept,
                Map<String, Object> isolate) {
            this.id = id;
            this.name = name;
            this.config = config;
            this.group = group;
            this.disabled = disabled;
            this.inject = inject;
            this.intercept = intercept;
            this.isolate = isolate;
        }

        /** Restores the captured values into {@code target}. */
        public void restore(EntryOptions target) {
            target.id = id;
            target.name = name;
            target.config = config;
            target.group = group;
            target.disabled = disabled;
            target.inject = inject;
            target.intercept = intercept;
            target.isolate = isolate;
        }
    }

    /** Fluent builder for {@link EntryOptions}. */
    public static final class Builder {
        private final EntryOptions options = new EntryOptions();

        public Builder id(String id) {
            options.id = id;
            return this;
        }

        public Builder name(String name) {
            options.name = name;
            return this;
        }

        public Builder config(Object config) {
            options.config = config;
            return this;
        }

        public Builder group(boolean group) {
            options.group = group;
            return this;
        }

        public Builder disabled(boolean disabled) {
            options.disabled = disabled;
            return this;
        }

        public Builder inject(Map<String, Object> inject) {
            options.inject = inject;
            return this;
        }

        public Builder intercept(Map<String, Object> intercept) {
            options.intercept = intercept;
            return this;
        }

        public Builder isolate(Map<String, Object> isolate) {
            options.isolate = isolate;
            return this;
        }

        public EntryOptions build() {
            return options;
        }
    }
}
