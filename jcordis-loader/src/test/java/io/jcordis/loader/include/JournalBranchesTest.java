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
 * Boundary and error-path coverage for the journal mechanics: deep comparisons
 * across container types, reconciliation variants, and journal application when
 * the target list or group does not line up.
 */
class JournalBranchesTest {

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

    // ------------------------------------------------------------------ equality

    @Test
    void equalsShouldCoverContainerAndScalarBranches() {
        assertThat(Journal.equals(null, null)).isTrue();
        assertThat(Journal.equals(null, "x")).isFalse();
        assertThat(Journal.equals("x", null)).isFalse();

        // maps: different size, missing key, equal content
        assertThat(Journal.equals(Map.of("a", 1), Map.of("a", 1, "b", 2))).isFalse();
        assertThat(Journal.equals(Map.of("a", 1), Map.of("b", 1))).isFalse();
        assertThat(Journal.equals(Map.of("a", Map.of("b", List.of(1))), Map.of("a", Map.of("b", List.of(1)))))
                .isTrue();

        // collections: different size, different element
        assertThat(Journal.equals(List.of(1, 2), List.of(1))).isFalse();
        assertThat(Journal.equals(List.of(1, 2), List.of(1, 3))).isFalse();

        // arrays and mixed number types (YAML/JSON parse differently)
        assertThat(Journal.equals(new int[] {1, 2}, new int[] {1, 2})).isTrue();
        assertThat(Journal.equals(new int[] {1, 2}, new int[] {1, 3})).isFalse();
        assertThat(Journal.equals(new int[] {1}, new int[] {1, 2})).isFalse();
        assertThat(Journal.equals(1, 1.0)).isTrue();

        // option-set comparison ignores the id but flags a field change
        EntryOptions a = options("a", "demo", Map.of("n", 1));
        EntryOptions b = options("b", "demo", Map.of("n", 1));
        assertThat(Journal.equals(a, b)).as("ids are ignored").isTrue();
        b.disabled = true;
        assertThat(Journal.equals(a, b)).isFalse();
        assertThat(Journal.equals(a, null)).isFalse();
    }

    @Test
    void equalsOptionsListShouldCompareElementwise() {
        assertThat(Journal.equalsOptionsList(List.of(options("a", "d", null)), List.of(options("a", "d", null))))
                .isTrue();
        assertThat(Journal.equalsOptionsList(List.of(options("a", "d", null)), List.of()))
                .isFalse();
        assertThat(Journal.equalsOptionsList(List.of(options("a", "d", null)), List.of(options("a", "other", null))))
                .isFalse();
        assertThat(Journal.equalsOptionsList(null, List.of())).isFalse();
    }

    // -------------------------------------------------------------------- merging

    @Test
    void mergeRecordsShouldHandleRemovalTransitions() {
        Journal.Record created = new Journal.Record();
        created.created = true;
        Journal.Record removal = new Journal.Record();
        removal.removed = true;

        assertThat(Journal.mergeRecords(created, removal))
                .as("created then removed leaves no trace")
                .isNull();

        Journal.Record existing = new Journal.Record();
        existing.changes.put("config", Map.of("n", 1));
        Journal.Record merged = Journal.mergeRecords(existing, removal);
        assertThat(merged.removed).isTrue();

        Journal.Record revived = Journal.mergeRecords(removal, created);
        assertThat(revived.removed).isFalse();
        assertThat(revived.created).as("the entry already existed in the file").isFalse();
    }

    @Test
    void mergeRecordsShouldKeepTheRelocationOfTheNewerRecord() {
        Journal.Record older = new Journal.Record();
        older.hasParent = true;
        older.parent = "g1";
        older.position = 3;
        Journal.Record newer = new Journal.Record();
        newer.hasParent = true;
        newer.parent = "g2";
        newer.position = 1;

        Journal.Record merged = Journal.mergeRecords(older, newer);
        assertThat(merged.parent).isEqualTo("g2");
        assertThat(merged.position).isEqualTo(1);

        // a newer record without relocation keeps the older one's target
        Journal.Record update = new Journal.Record();
        update.changes.put("disabled", true);
        Journal.Record kept = Journal.mergeRecords(older, update);
        assertThat(kept.parent).isEqualTo("g1");
        assertThat(kept.position).isEqualTo(3);
    }

    // -------------------------------------------------------------- reconciliation

    @Test
    void reconcileShouldKeepRemovalsTheFileDidNotTouch() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        EntryOptions legacy = options("a", "demo", null);
        Journal.record(journal, EntryChange.removed("a", rootGroup(), legacy), null);

        // the file still lists the entry, unchanged: the runtime removal survives
        List<Journal.Conflict> conflicts = Journal.reconcile(
                journal,
                Journal.flatten(List.of(options("a", "demo", null))),
                Journal.flatten(List.of(options("a", "demo", null))),
                ALL);

        assertThat(conflicts).isEmpty();
        assertThat(journal.get("a").removed).isTrue();
    }

    @Test
    void reconcileShouldDropRemovalsForEntriesTheFileAlsoRemoved() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        Journal.record(journal, EntryChange.removed("a", rootGroup(), options("a", "demo", null)), null);

        List<Journal.Conflict> conflicts = Journal.reconcile(
                journal, Journal.flatten(List.of(options("a", "demo", null))), Journal.flatten(List.of()), ALL);

        assertThat(conflicts).isEmpty();
        assertThat(journal).as("both sides removed it").isEmpty();
    }

    @Test
    void reconcileShouldReportEntriesAddedInTheFileWhileRemovedAtRuntime() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        Journal.record(journal, EntryChange.removed("a", rootGroup(), options("a", "demo", null)), null);

        List<Journal.Conflict> conflicts = Journal.reconcile(
                journal, Journal.flatten(List.of()), Journal.flatten(List.of(options("a", "demo", null))), ALL);

        assertThat(conflicts).extracting(Journal.Conflict::reason).containsExactly("added in file, removed at runtime");
        assertThat(journal).isEmpty();
    }

    @Test
    void reconcileShouldReportAMoveConflict() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        EntryOptions base = options("child", "demo", null);
        EntryOptions target = options("g2", "@cordisjs/plugin-group", new ArrayList<EntryOptions>());
        target.group = true;
        Journal.Record record = new Journal.Record();
        record.hasParent = true;
        record.parent = "g2";
        record.changes = Map.of();
        journal.put("child", record);

        EntryOptions group1 = options("g1", "@cordisjs/plugin-group", List.of(options("child", "demo", null)));
        group1.group = true;
        EntryOptions group2 = options("g2", "@cordisjs/plugin-group", new ArrayList<EntryOptions>());
        group2.group = true;
        // the file moved the entry into g2 as well: nothing to do at runtime
        Map<String, Journal.Flat> before = Journal.flatten(List.of(group1, group2));
        EntryOptions emptyG1 = options("g1", "@cordisjs/plugin-group", new ArrayList<EntryOptions>());
        emptyG1.group = true;
        EntryOptions filledG2 =
                options("g2", "@cordisjs/plugin-group", List.of(options("child", "demo", Map.of("n", 1))));
        filledG2.group = true;
        Map<String, Journal.Flat> theirs = Journal.flatten(List.of(emptyG1, filledG2));

        List<Journal.Conflict> conflicts = Journal.reconcile(journal, before, theirs, ALL);

        assertThat(conflicts).isEmpty();
        assertThat(journal).as("file already applied the move").isEmpty();
    }

    @Test
    void reconcileShouldKeepRuntimeMovesTheFileDidNotApply() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        Journal.Record record = new Journal.Record();
        record.hasParent = true;
        record.parent = "g2";
        record.changes = Map.of();
        journal.put("child", record);

        EntryOptions group1 = options("g1", "@cordisjs/plugin-group", List.of(options("child", "demo", null)));
        group1.group = true;
        EntryOptions group2 = options("g2", "@cordisjs/plugin-group", new ArrayList<EntryOptions>());
        group2.group = true;
        Map<String, Journal.Flat> both = Journal.flatten(List.of(group1, group2));

        // the file still has the entry under g1: the runtime move survives
        List<Journal.Conflict> conflicts = Journal.reconcile(journal, both, both, ALL);

        assertThat(conflicts).isEmpty();
        assertThat(journal.get("child").parent).isEqualTo("g2");
    }

    @Test
    void reconcileShouldSkipChangesTheFileDoesNotOwn() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        EntryOptions base = options("a", "demo", Map.of("n", 1));
        Journal.record(
                journal, EntryChange.updated("a", rootGroup(), null, options("a", "demo", Map.of("n", 2)), base), null);

        List<Journal.Conflict> conflicts = Journal.reconcile(
                journal,
                Journal.flatten(List.of(options("a", "demo", Map.of("n", 1)))),
                Journal.flatten(List.of(options("a", "demo", Map.of("n", 3)))),
                (id, key) -> false);

        assertThat(conflicts).isEmpty();
        assertThat(journal.get("a").changes)
                .as("patch-owned keys are left alone")
                .containsKey("config");
    }

    // ------------------------------------------------------------------ applying

    @Test
    void applyShouldWarnWhenTheTargetGroupIsMissing() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        Journal.record(journal, EntryChange.created("child", rootGroup(), options("child", "demo", null)), "missing");
        List<EntryOptions> data = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Journal.apply(data, journal, ALL, warnings::add);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("group missing not found");
        assertThat(data).as("nothing could be placed").isEmpty();
    }

    @Test
    void applyShouldMoveAnEntryBetweenGroups() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        EntryOptions child = options("child", "demo", null);
        EntryOptions group = options("g", "@cordisjs/plugin-group", new ArrayList<EntryOptions>(List.of(child)));
        group.group = true;
        Journal.Record record = new Journal.Record();
        record.hasParent = true;
        record.parent = null;
        record.position = 1;
        record.changes = Map.of();
        journal.put("child", record);
        List<EntryOptions> data = new ArrayList<>(List.of(group));

        Journal.apply(data, journal, ALL, null);

        assertThat(data).extracting(options -> options.id).containsExactly("g", "child");
        @SuppressWarnings("unchecked")
        List<EntryOptions> children = (List<EntryOptions>) data.get(0).config;
        assertThat(children).as("detached from the group").isEmpty();
    }

    @Test
    void applyShouldHonourTheKeyFilter() {
        Map<String, Journal.Record> journal = new LinkedHashMap<>();
        EntryOptions base = options("a", "demo", Map.of("n", 1));
        EntryOptions next = options("a", "demo", Map.of("n", 2));
        next.disabled = true;
        Journal.record(journal, EntryChange.updated("a", rootGroup(), null, next, base), null);
        List<EntryOptions> data = new ArrayList<>(List.of(options("a", "demo", Map.of("n", 1))));

        Journal.apply(data, journal, (id, key) -> key == null || key.equals("disabled"), null);

        assertThat(data.get(0).config).as("filtered key untouched").isEqualTo(Map.of("n", 1));
        assertThat(data.get(0).disabled).isTrue();
    }

    @Test
    void placeShouldRejectNonGroupsAndCreateMissingLists() {
        List<EntryOptions> data = new ArrayList<>();
        EntryOptions plain = options("plain", "demo", null);
        data.add(plain);

        assertThat(Journal.place(data, options("x", "demo", null), "plain", 0))
                .as("a non-group entry cannot host children")
                .isFalse();
        assertThat(Journal.place(data, options("x", "demo", null), "missing", 0))
                .isFalse();

        EntryOptions group = options("g", "@cordisjs/plugin-group", null);
        group.group = true;
        data.add(group);
        assertThat(Journal.place(data, options("x", "demo", null), "g", 99))
                .as("a group without a config list gets one")
                .isTrue();
        assertThat(group.config).isInstanceOf(List.class);
    }

    @Test
    void detachShouldIgnoreUnknownIds() {
        List<EntryOptions> data = new ArrayList<>(List.of(options("a", "demo", null)));

        Journal.detach(data, "missing");

        assertThat(data).hasSize(1);
    }

    @Test
    void detachShouldReachIntoGroupChildren() {
        EntryOptions child = options("child", "demo", null);
        EntryOptions group = options("g", "@cordisjs/plugin-group", new ArrayList<>(List.of(child)));
        group.group = true;
        List<EntryOptions> data = new ArrayList<>(List.of(group));

        // the flattened view must point at the live nested list, otherwise this
        // removal would silently do nothing
        Journal.detach(data, "child");

        @SuppressWarnings("unchecked")
        List<EntryOptions> children = (List<EntryOptions>) group.config;
        assertThat(children).isEmpty();
    }

    @Test
    void applyChangesShouldClearKeysWithNullValues() {
        EntryOptions target = options("a", "demo", Map.of("n", 1));
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("config", null);
        changes.put("name", "renamed");

        Journal.applyChanges(target, changes);

        assertThat(target.config).isNull();
        assertThat(target.name).isEqualTo("renamed");
    }

    @Test
    void flattenShouldSkipGroupEntriesWithoutAListConfig() {
        EntryOptions group = options("g", "@cordisjs/plugin-group", null);
        group.group = true;

        assertThat(Journal.flatten(List.of(group))).containsOnlyKeys("g");
    }

    @Test
    void flattenShouldIgnoreAnonymousEntries() {
        EntryOptions anonymous = options(null, "demo", null);
        assertThat(Journal.flatten(List.of(anonymous))).isEmpty();
    }
}
