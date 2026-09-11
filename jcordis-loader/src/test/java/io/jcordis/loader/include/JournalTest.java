package io.jcordis.loader.include;

import static org.assertj.core.api.Assertions.assertThat;

import io.jcordis.core.context.Context;
import io.jcordis.loader.EntryChange;
import io.jcordis.loader.EntryGroup;
import io.jcordis.loader.EntryOptions;
import io.jcordis.loader.Loader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the journal mechanics ported from Cordis's
 * {@code journal.ts}: record/merge folding, option diffs, applying a journal
 * onto a config list, and three-way reconciliation where the file wins.
 */
class JournalTest {

    private static EntryOptions options(String id, String name, Object config) {
        EntryOptions options = new EntryOptions();
        options.id = id;
        options.name = name;
        options.config = config;
        return options;
    }

    private static EntryGroup rootGroup() {
        return new Loader(Context.create()).root;
    }

    private static final Journal.Filter ALL = (id, key) -> true;

    @Test
    void diffShouldOnlyCarryChangedKeys() {
        Map<String, Object> changes =
                Journal.diff(options("a", "demo", Map.of("n", 1)), options("a", "demo", Map.of("n", 2)));

        assertThat(changes).containsOnlyKeys("config");
        assertThat(changes.get("config")).isEqualTo(Map.of("n", 2));
    }

    @Test
    void diffShouldCarryClearedKeysAsNull() {
        EntryOptions legacy = options("a", "demo", Map.of("n", 1));
        legacy.disabled = false;
        EntryOptions next = options("a", "demo", Map.of("n", 1));
        next.name = "other";

        Map<String, Object> changes = Journal.diff(legacy, next);

        assertThat(changes).containsOnlyKeys("name", "disabled");
        assertThat(changes.get("name")).isEqualTo("other");
        assertThat(changes.get("disabled"))
                .as("a cleared key is carried as null")
                .isNull();
    }

    @Test
    void createdThenRemovedShouldLeaveNoTrace() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        EntryGroup group = rootGroup();
        EntryOptions created = options("a", "demo", null);

        Journal.record(journal, EntryChange.created("a", group, created), null);
        assertThat(journal).containsKey("a");

        Journal.record(journal, EntryChange.removed("a", group, created), null);
        assertThat(journal).as("created and removed before writing").doesNotContainKey("a");
    }

    @Test
    void removedThenCreatedShouldNotBeReportedAsCreated() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        EntryGroup group = rootGroup();
        EntryOptions first = options("a", "demo", null);
        EntryOptions second = options("a", "demo", Map.of("n", 2));

        Journal.record(journal, EntryChange.removed("a", group, first), null);
        Journal.record(journal, EntryChange.created("a", group, second), null);

        assertThat(journal.get("a").removed).isFalse();
        assertThat(journal.get("a").created)
                .as("the entry already existed in the file")
                .isFalse();
    }

    @Test
    void consecutiveUpdatesShouldFoldIntoOneRecord() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        EntryGroup group = rootGroup();
        EntryOptions base = options("a", "demo", Map.of("n", 1));
        EntryOptions second = options("a", "demo", Map.of("n", 2));
        EntryOptions third = options("a", "demo", Map.of("n", 3));
        third.disabled = true;

        Journal.record(journal, EntryChange.updated("a", group, null, second, base), null);
        Journal.record(journal, EntryChange.updated("a", group, null, third, second), null);

        assertThat(journal).hasSize(1);
        Journal.Record record = journal.get("a");
        assertThat(record.changes).containsEntry("disabled", Boolean.TRUE);
        assertThat(record.changes.get("config")).isEqualTo(Map.of("n", 3));
    }

    @Test
    void applyShouldUpsertEntriesIntoLists() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        EntryGroup group = rootGroup();
        Journal.record(journal, EntryChange.created("a", group, options("a", "demo", Map.of("n", 1))), null);
        // position -1 (the change was recorded outside a real create) clamps to the head
        journal.get("a").position = 1;
        List<EntryOptions> data = new ArrayList<>(List.of(options("b", "other", null)));

        Journal.apply(data, journal, ALL, null);

        assertThat(data).extracting(options -> options.id).containsExactly("b", "a");
        assertThat(data.get(1).config).isEqualTo(Map.of("n", 1));
    }

    @Test
    void applyShouldUpdateExistingEntriesInPlace() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        EntryGroup group = rootGroup();
        EntryOptions base = options("a", "demo", Map.of("n", 1));
        Journal.record(
                journal, EntryChange.updated("a", group, null, options("a", "demo", Map.of("n", 2)), base), null);
        List<EntryOptions> data = new ArrayList<>(List.of(options("a", "demo", Map.of("n", 1))));

        Journal.apply(data, journal, ALL, null);

        assertThat(data).hasSize(1);
        assertThat(data.get(0).config).isEqualTo(Map.of("n", 2));
    }

    @Test
    void applyShouldRemoveEntries() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        EntryGroup group = rootGroup();
        EntryOptions removed = options("a", "demo", null);
        Journal.record(journal, EntryChange.removed("a", group, removed), null);
        List<EntryOptions> data = new ArrayList<>(List.of(removed, options("b", "demo", null)));

        Journal.apply(data, journal, ALL, null);

        assertThat(data).extracting(options -> options.id).containsExactly("b");
    }

    @Test
    void applyShouldPlaceEntriesInsideGroups() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        EntryGroup group = rootGroup();
        EntryOptions groupOptions = options("g", "@cordisjs/plugin-group", new ArrayList<EntryOptions>());
        groupOptions.group = true;
        Journal.record(journal, EntryChange.created("child", group, options("child", "demo", null)), "g");
        List<EntryOptions> data = new ArrayList<>(List.of(groupOptions));

        Journal.apply(data, journal, ALL, null);

        @SuppressWarnings("unchecked")
        List<EntryOptions> children = (List<EntryOptions>) data.get(0).config;
        assertThat(children).extracting(options -> options.id).containsExactly("child");
    }

    @Test
    void reconcileShouldLetTheFileWinAndReportTheConflict() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        EntryGroup group = rootGroup();
        EntryOptions base = options("a", "demo", Map.of("n", 1));
        EntryOptions runtime = options("a", "demo", Map.of("n", 2));
        Journal.record(journal, EntryChange.updated("a", group, null, runtime, base), null);

        Map<String, Journal.Flat> before = Journal.flatten(List.of(options("a", "demo", Map.of("n", 1))));
        Map<String, Journal.Flat> theirs = Journal.flatten(List.of(options("a", "demo", Map.of("n", 3))));

        List<Journal.Conflict> conflicts = Journal.reconcile(journal, before, theirs, ALL);

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).reason()).contains("modified both in file and at runtime");
        assertThat(journal).as("the conflicting key was dropped").isEmpty();
    }

    @Test
    void reconcileShouldKeepRuntimeChangesTheFileDidNotTouch() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        EntryGroup group = rootGroup();
        EntryOptions base = options("a", "demo", Map.of("n", 1));
        EntryOptions runtime = options("a", "demo", Map.of("n", 2));
        runtime.disabled = true;
        Journal.record(journal, EntryChange.updated("a", group, null, runtime, base), null);

        Map<String, Journal.Flat> before = Journal.flatten(List.of(options("a", "demo", Map.of("n", 1))));
        // the file only renamed the entry: the config change stays in the journal
        Map<String, Journal.Flat> theirs = Journal.flatten(List.of(options("a", "renamed", Map.of("n", 1))));

        List<Journal.Conflict> conflicts = Journal.reconcile(journal, before, theirs, ALL);

        assertThat(conflicts).isEmpty();
        assertThat(journal.get("a").changes)
                .as("the file did not touch config, so the runtime change stays")
                .containsOnlyKeys("config", "disabled");
    }

    @Test
    void reconcileShouldDropChangesForEntriesTheFileRemoved() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        EntryGroup group = rootGroup();
        EntryOptions base = options("a", "demo", Map.of("n", 1));
        Journal.record(
                journal, EntryChange.updated("a", group, null, options("a", "demo", Map.of("n", 2)), base), null);

        Map<String, Journal.Flat> before = Journal.flatten(List.of(options("a", "demo", Map.of("n", 1))));
        Map<String, Journal.Flat> theirs = Journal.flatten(List.of());

        List<Journal.Conflict> conflicts = Journal.reconcile(journal, before, theirs, ALL);

        assertThat(conflicts)
                .extracting(Journal.Conflict::reason)
                .containsExactly("removed in file, modified at runtime");
        assertThat(journal).isEmpty();
    }

    @Test
    void flattenShouldIndexNestedGroupMembers() {
        EntryOptions group = options("g", "@cordisjs/plugin-group", null);
        group.group = true;
        group.config = new ArrayList<>(List.of(options("child", "demo", null)));

        Map<String, Journal.Flat> flat = Journal.flatten(List.of(group, options("top", "demo", null)));

        assertThat(flat).containsOnlyKeys("g", "child", "top");
        assertThat(flat.get("child").parent).isEqualTo("g");
        assertThat(flat.get("top").parent).isNull();
    }

    @Test
    void equalsShouldCompareNestedStructures() {
        assertThat(Journal.equals(Map.of("a", List.of(1, 2)), Map.of("a", List.of(1, 2))))
                .isTrue();
        assertThat(Journal.equals(Map.of("a", List.of(1, 2)), Map.of("a", List.of(1, 3))))
                .isFalse();
        assertThat(Journal.equals(1, 1L)).as("YAML/JSON numbers differ in type").isTrue();
    }
}
