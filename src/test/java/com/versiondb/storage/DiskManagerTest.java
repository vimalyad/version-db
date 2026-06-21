package com.versiondb.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.versiondb.shared.Constants;
import com.versiondb.shared.StorageException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskManagerTest {

    @TempDir
    Path tmp;

    private Path db() {
        return tmp.resolve("test.db");
    }

    @Test
    void newDatabaseStartsWithZeroPages() {
        try (DiskManager dm = new DiskManager(db())) {
            assertEquals(0, dm.getNumPages());
        }
    }

    @Test
    void allocateReturnsSequentialIdsAndGrowsCount() {
        try (DiskManager dm = new DiskManager(db())) {
            assertEquals(0, dm.allocatePage());
            assertEquals(1, dm.allocatePage());
            assertEquals(2, dm.allocatePage());
            assertEquals(3, dm.getNumPages());
        }
    }

    @Test
    void writeThenReadRoundTripsTupleData() {
        try (DiskManager dm = new DiskManager(db())) {
            int pid = dm.allocatePage();
            Page page = Page.create(pid);
            page.setLsn(55L);
            int slot = page.insertTuple(new byte[] {1, 2, 3, 4});
            dm.writePage(pid, page);

            Page read = dm.readPage(pid);
            assertEquals(pid, read.getPageId());
            assertEquals(55L, read.getLsn());
            assertArrayEquals(new byte[] {1, 2, 3, 4}, read.getTuple(slot));
        }
    }

    @Test
    void freshlyAllocatedPageIsReadableAndEmpty() {
        try (DiskManager dm = new DiskManager(db())) {
            int pid = dm.allocatePage();
            Page read = dm.readPage(pid);
            assertEquals(pid, read.getPageId());
            assertEquals(0, read.getNumSlots());
        }
    }

    @Test
    void pageCountAndDataPersistAcrossReopen() {
        Path path = db();
        int pid;
        try (DiskManager dm = new DiskManager(path)) {
            pid = dm.allocatePage();
            dm.allocatePage();
            Page page = Page.create(pid);
            page.insertTuple(new byte[] {9, 8, 7});
            dm.writePage(pid, page);
        }

        try (DiskManager dm = new DiskManager(path)) {
            assertEquals(2, dm.getNumPages());
            assertArrayEquals(new byte[] {9, 8, 7}, dm.readPage(pid).getTuple(0));
        }
    }

    @Test
    void readingUnallocatedPageThrows() {
        try (DiskManager dm = new DiskManager(db())) {
            assertThrows(StorageException.class, () -> dm.readPage(0));
            dm.allocatePage();
            assertThrows(StorageException.class, () -> dm.readPage(1));
        }
    }

    @Test
    void writingUnallocatedPageThrows() {
        try (DiskManager dm = new DiskManager(db())) {
            assertThrows(StorageException.class, () -> dm.writePage(0, Page.create(0)));
        }
    }

    @Test
    void onDiskCorruptionIsDetectedOnRead() throws IOException {
        Path path = db();
        int pid;
        try (DiskManager dm = new DiskManager(path)) {
            pid = dm.allocatePage();
            Page page = Page.create(pid);
            page.insertTuple(new byte[] {4, 5, 6, 7});
            dm.writePage(pid, page);
        }

        // Corrupt a byte in the page body directly in the file.
        long bodyByte = (long) Constants.PAGE_SIZE + Constants.PAGE_SIZE - 1;
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.seek(bodyByte);
            int b = raf.read();
            raf.seek(bodyByte);
            raf.write(b ^ 0xFF);
        }

        try (DiskManager dm = new DiskManager(path)) {
            assertThrows(StorageException.class, () -> dm.readPage(pid));
        }
    }

    @Test
    void tombstoneSurvivesWriteAndRead() {
        try (DiskManager dm = new DiskManager(db())) {
            int pid = dm.allocatePage();
            Page page = Page.create(pid);
            int slot = page.insertTuple(new byte[] {1, 1, 1});
            page.deleteTuple(slot);
            dm.writePage(pid, page);

            assertNull(dm.readPage(pid).getTuple(slot));
        }
    }

    @Test
    void databaseFileExistsAfterCreation() {
        try (DiskManager dm = new DiskManager(db())) {
            dm.allocatePage();
        }
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(db()));
    }
}
