package com.openmason.main.systems.menus.dialogs;

import com.openmason.api.asset.AssetEditorSection;
import com.openmason.api.asset.AssetSectionContribution;
import com.openmason.api.asset.AssetSectionHost;
import com.openmason.api.asset.AssetSurface;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How a window's contributed asset sections are created, ordered, written and confirmed (#431). */
@Tag("regression")
class AssetSectionSetTest {

    /** A plugin kind's manifest model: an id and a type sections append to. */
    record Doc(String objectId, String entityType) {
        Doc withEntityType(String type) {
            return new Doc(objectId, type);
        }
    }

    static final AssetSurface<Doc> EDITOR = AssetSurface.of("test.editor", "Test Editor", Doc.class);
    static final AssetSurface<Doc> OTHER = AssetSurface.of("other.editor", "Other Editor", Doc.class);

    private static final AssetSectionHost HOST = new AssetSectionHost() {
        @Override public AssetSurface<?> surface() { return EDITOR; }
        @Override public String objectId() { return "test:thing"; }
        @Override public String objectType() { return ""; }
        @Override public boolean texturePayload() { return false; }
        @Override public void markDirty() { }
    };

    private static final List<AssetSectionSet.Tab> CORE = List.of(
            new AssetSectionSet.Tab("core.metadata", "Metadata", 0, null),
            new AssetSectionSet.Tab("core.states", "States", 100, null),
            new AssetSectionSet.Tab("core.sounds", "Sounds", 300, null));

    @AfterEach
    void noSections() {
        AssetSectionSet.setSource(null);
    }

    /** Appends its name to the entity type on write; reports what it was configured to. */
    private static final class Probe implements AssetEditorSection<Doc> {
        final String name;
        Placement placement = Placement.TAB;
        String error;
        String conflict;
        boolean asks;
        Runnable pending;
        int loads;
        boolean throwOnWrite;

        Probe(String name) {
            this.name = name;
        }

        @Override public String title() { return name; }
        @Override public Placement placement() { return placement; }
        @Override public void load(Doc manifest) { loads++; }
        @Override public void render() { }
        @Override public String validate() { return error; }
        @Override public String conflict() { return conflict; }

        @Override
        public boolean confirmWrite(Runnable proceed) {
            if (!asks) return false;
            pending = proceed;
            return true;
        }

        @Override
        public Doc write(Doc doc) {
            if (throwOnWrite) throw new IllegalStateException("boom");
            return doc.withEntityType(doc.entityType() + "+" + name);
        }
    }

    private static AssetSectionContribution<Doc> contribution(String id, int order, Probe probe) {
        return new AssetSectionContribution<>(id, EDITOR, order, h -> probe);
    }

    private static Doc doc() {
        return new Doc("test:thing", "mob");
    }

    @Test
    void tabsMergeByOrderAndMetadataSectionsStayOffTheStrip() {
        Probe late = new Probe("Late");
        Probe early = new Probe("Early");
        Probe inline = new Probe("Inline");
        inline.placement = AssetEditorSection.Placement.METADATA;
        AssetSectionSet.setSource(() -> List.of(contribution("p.late", 400, late),
                contribution("p.early", 150, early), contribution("p.inline", 50, inline),
                // another surface's section is never created here
                new AssetSectionContribution<>("p.other", OTHER, 10, h -> {
                    throw new AssertionError("created for the wrong surface");
                })));
        AssetSectionSet<Doc> set = new AssetSectionSet<>(EDITOR, HOST);
        set.load(doc());

        List<String> labels = set.tabs(CORE).stream().map(AssetSectionSet.Tab::label).toList();
        assertEquals(List.of("Metadata", "States", "Early", "Sounds", "Late"), labels);
        assertEquals(1, early.loads);
        assertEquals(1, inline.loads);
    }

    @Test
    void writesChainInOrderAndAFailingSectionKeepsTheManifest() {
        Probe a = new Probe("a");
        Probe broken = new Probe("broken");
        broken.throwOnWrite = true;
        Probe b = new Probe("b");
        AssetSectionSet.setSource(() -> List.of(contribution("p.b", 300, b), contribution("p.broken", 200, broken),
                contribution("p.a", 100, a)));
        AssetSectionSet<Doc> set = new AssetSectionSet<>(EDITOR, HOST);
        set.load(doc());
        assertEquals("mob+a+b", set.write(doc()).entityType());
    }

    @Test
    void noSectionsLeavesTheManifestUntouched() {
        AssetSectionSet<Doc> set = new AssetSectionSet<>(EDITOR, HOST);
        set.load(doc());
        assertTrue(set.isEmpty());
        Doc d = doc();
        assertSame(d, set.write(d));
        assertNull(set.firstError());
        assertEquals(CORE, set.tabs(CORE));
    }

    @Test
    void firstErrorAndConflictsComeFromTheSections() {
        Probe ok = new Probe("ok");
        Probe bad = new Probe("bad");
        bad.error = "nope";
        bad.conflict = "taken";
        AssetSectionSet.setSource(() -> List.of(contribution("p.ok", 100, ok), contribution("p.bad", 200, bad)));
        AssetSectionSet<Doc> set = new AssetSectionSet<>(EDITOR, HOST);
        set.load(doc());
        assertEquals("nope", set.firstError().message());
        assertSame(bad, set.firstError().section());
        assertEquals(List.of("taken"), set.conflicts().stream().map(AssetSectionSet.Problem::message).toList());
    }

    @Test
    void aWriteWaitsForEveryConflictedSectionToConfirm() {
        Probe asker = new Probe("asker");
        asker.conflict = "duplicate id";
        asker.asks = true;
        Probe silent = new Probe("silent");
        silent.conflict = "warn only"; // does not ask: the write goes on
        AssetSectionSet.setSource(() -> List.of(contribution("p.asker", 100, asker),
                contribution("p.silent", 200, silent)));
        AssetSectionSet<Doc> set = new AssetSectionSet<>(EDITOR, HOST);
        set.load(doc());

        AtomicInteger writes = new AtomicInteger();
        set.confirmThenWrite(writes::incrementAndGet);
        assertEquals(0, writes.get(), "the asking section holds the write");
        asker.pending.run(); // the person confirmed
        assertEquals(1, writes.get());
    }

    @Test
    void sectionsRegisteredLaterAreCreatedOnTheNextLoad() {
        List<AssetSectionContribution<?>> registered = new ArrayList<>();
        AssetSectionSet.setSource(() -> List.copyOf(registered));
        AssetSectionSet<Doc> set = new AssetSectionSet<>(EDITOR, HOST);
        set.load(doc());
        assertTrue(set.isEmpty());
        Probe p = new Probe("late");
        registered.add(contribution("p.late", 100, p));
        set.load(doc());
        assertEquals(1, p.loads);
        assertEquals("mob+late", set.write(doc()).entityType());
    }
}
