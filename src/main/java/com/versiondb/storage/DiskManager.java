package com.versiondb.storage;

import com.versiondb.shared.Constants;
import com.versiondb.shared.StorageException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/**
 * Owns the single database file and performs raw page I/O. This is the only
 * component that touches the file directly; everything above it works in terms
 * of {@link Page} objects addressed by page id.
 *
 * <p>File layout: a one-page file header (storing the total page count) followed
 * by the data pages. Page {@code N} therefore lives at byte offset
 * {@code PAGE_SIZE + N * PAGE_SIZE}.
 *
 * <p><b>Invariant:</b> only the buffer pool may call {@link #writePage}; no other
 * component writes to disk.
 */
public final class DiskManager implements AutoCloseable {

    /** The first page-sized block of the file is reserved for the file header. */
    private static final int FILE_HEADER_SIZE = Constants.PAGE_SIZE;

    /** Byte offset within the file header where the total page count is stored. */
    private static final int OFF_NUM_PAGES = 0;

    private final RandomAccessFile file;
    private int numPages;

    /**
     * Open (or create) the database file at the given path. A new file is
     * initialised with a header recording zero pages; an existing file's page
     * count is read back from its header.
     */
    public DiskManager(Path dbPath) {
        try {
            this.file = new RandomAccessFile(dbPath.toFile(), "rw");
            if (file.length() < FILE_HEADER_SIZE) {
                this.numPages = 0;
                writeHeader();
            } else {
                file.seek(OFF_NUM_PAGES);
                this.numPages = file.readInt();
            }
        } catch (IOException e) {
            throw new StorageException("failed to open database file " + dbPath, e);
        }
    }

    /** @return the number of pages currently allocated in the file. */
    public synchronized int getNumPages() {
        return numPages;
    }

    /**
     * Read and deserialize the page with the given id.
     *
     * @throws StorageException if the page id is out of range, on I/O failure, or
     *         if the page fails its checksum (corruption)
     */
    public synchronized Page readPage(int pageId) {
        checkPageId(pageId);
        byte[] image = new byte[Constants.PAGE_SIZE];
        try {
            file.seek(offsetOf(pageId));
            file.readFully(image);
        } catch (IOException e) {
            throw new StorageException("failed to read page " + pageId, e);
        }
        return Page.deserialize(image);
    }

    /**
     * Serialize and write a page to disk, then force it durable.
     *
     * @throws StorageException if the page id is out of range or on I/O failure
     */
    public synchronized void writePage(int pageId, Page page) {
        checkPageId(pageId);
        try {
            file.seek(offsetOf(pageId));
            file.write(page.serialize());
            file.getFD().sync();
        } catch (IOException e) {
            throw new StorageException("failed to write page " + pageId, e);
        }
    }

    /**
     * Allocate a new page at the end of the file, write an empty initialised page
     * there so it is immediately readable, persist the updated page count, and
     * return the new page id.
     */
    public synchronized int allocatePage() {
        int newPageId = numPages;
        try {
            file.seek(offsetOf(newPageId));
            file.write(Page.create(newPageId).serialize());
            numPages++;
            writeHeader();
            file.getFD().sync();
        } catch (IOException e) {
            throw new StorageException("failed to allocate page " + newPageId, e);
        }
        return newPageId;
    }

    @Override
    public synchronized void close() {
        try {
            file.close();
        } catch (IOException e) {
            throw new StorageException("failed to close database file", e);
        }
    }

    private void writeHeader() throws IOException {
        file.seek(OFF_NUM_PAGES);
        file.writeInt(numPages);
    }

    private void checkPageId(int pageId) {
        if (pageId < 0 || pageId >= numPages) {
            throw new StorageException("page id " + pageId + " out of range (numPages " + numPages + ")");
        }
    }

    private static long offsetOf(int pageId) {
        return (long) FILE_HEADER_SIZE + (long) pageId * Constants.PAGE_SIZE;
    }
}
