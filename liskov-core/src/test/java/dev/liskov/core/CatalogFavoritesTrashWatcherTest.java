package dev.liskov.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CatalogFavoritesTrashWatcherTest {
    @Test
    void folderCatalogFavoritesTrashAndWatcher(@TempDir Path tmp) throws Exception {
        FolderCatalog cat = FolderCatalog.loadOrCreate(tmp);
        assertEquals("default", cat.folders.get(0).id);
        var f = cat.createFolder("Harbor Builds", SemanticColor.PLAYER);
        cat.setArchived(f.id, true);
        cat.assignItem("grand_harbor", f.id);
        cat.save(tmp);

        FolderCatalog reloaded = FolderCatalog.loadOrCreate(tmp);
        assertEquals(f.id, reloaded.folderOfItem("grand_harbor"));
        assertTrue(reloaded.findFolder(f.id).archived);
        assertEquals("player", reloaded.findFolder(f.id).colorRole);

        FavoritesStore favs = FavoritesStore.loadOrCreate(tmp.resolve("favorites.json"));
        assertTrue(favs.toggleFavorite("local", "schematics/a.litematic", "a.litematic", "litematic", false));
        favs.recordOpened("local", "schematics/a.litematic", "a.litematic", "litematic");
        favs.save(tmp.resolve("favorites.json"));
        FavoritesStore favs2 = FavoritesStore.loadOrCreate(tmp.resolve("favorites.json"));
        assertTrue(favs2.isFavorite("local", "schematics/a.litematic"));
        assertEquals(1, favs2.recent.size());

        Path workFile = tmp.resolve("work/script.mscript");
        LiskovPaths.writeAtomicUtf8(workFile, "print(1)");
        TrashManager trash = new TrashManager(tmp.resolve(".trash"));
        var trashed = trash.moveToTrash(workFile, "mscript");
        assertFalse(Files.exists(workFile));
        assertEquals(1, trash.list().size());
        Path restored = trash.restore(trashed.id);
        assertEquals(workFile.toAbsolutePath().normalize(), restored);
        assertTrue(Files.isRegularFile(workFile));

        ReloadWatcher watcher = new ReloadWatcher(100);
        watcher.watch("manhattan", tmp.resolve("work"));
        AtomicInteger seen = new AtomicInteger();
        watcher.addListener(ev -> seen.addAndGet(ev.changedFiles().size()));
        LiskovPaths.writeAtomicUtf8(workFile, "print(2)//updated");
        watcher.reloadNow();
        assertEquals(1, seen.get());
    }
}
