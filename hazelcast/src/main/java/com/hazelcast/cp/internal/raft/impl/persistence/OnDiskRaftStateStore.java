package com.hazelcast.cp.internal.raft.impl.persistence;

import com.hazelcast.cp.internal.raft.impl.RaftEndpoint;
import com.hazelcast.cp.internal.raft.impl.log.LogEntry;
import com.hazelcast.cp.internal.raft.impl.log.SnapshotEntry;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.serialization.impl.DefaultSerializationServiceBuilder;
import com.hazelcast.internal.serialization.impl.ObjectDataOutputStream;
import com.hazelcast.nio.IOUtil;
import com.hazelcast.nio.ObjectDataOutput;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Collection;

public class OnDiskRaftStateStore implements RaftStateStore {

    private final LogEntryRingBuffer logEntryRingBuffer;
    private BufferedRaf logRaf;
    private ObjectDataOutput logDataOut;
    private boolean flushCalledOnCurrFile;
    private File currentFile;
    private File danglingFile;
    private long nextEntryIndex;

    public OnDiskRaftStateStore(int maxUncommittedEntries) {
        this.nextEntryIndex = 1;
        this.logEntryRingBuffer = new LogEntryRingBuffer(maxUncommittedEntries, nextEntryIndex - 1);
    }

    @Override
    public void open() throws IOException {
        currentFile = fileWithIndex(nextEntryIndex);
        logRaf = createFile(currentFile);
        logDataOut = newObjectDataOutput(logRaf);
    }

    @Override
    public void persistEntry(LogEntry entry) throws IOException {
        if (entry.index() != nextEntryIndex) {
            throw new IllegalArgumentException(String.format(
                    "Expected entry index %,d, but got %,d", nextEntryIndex, entry.index()));
        }
        logEntryRingBuffer.addEntryOffset(logRaf.filePointer());
        logDataOut.writeObject(entry);
        nextEntryIndex++;
    }

    @Override
    public void persistSnapshot(SnapshotEntry snapshot) throws IOException {
        File newFile = fileWithIndex(snapshot.index());
        BufferedRaf newRaf = createFile(newFile);
        ObjectDataOutput newDataOut = newObjectDataOutput(newRaf);
        logDataOut.writeObject(snapshot);
        if (logEntryRingBuffer.topIndex() <= snapshot.index()) {
            return;
        }
        long copyFromOffset = logEntryRingBuffer.getEntryOffset(snapshot.index() + 1);
        long copyToOffset = newRaf.filePointer();
        logRaf.seek(copyFromOffset);
        logRaf.copyTo(newDataOut);
        logRaf.close();
        logRaf = newRaf;
        logDataOut = newDataOut;
        logEntryRingBuffer.adjustToNewFile(copyToOffset, snapshot.index());
        if (flushCalledOnCurrFile) {
            if (danglingFile != null) {
                IOUtil.delete(danglingFile);
            }
            danglingFile = currentFile;
        }
        currentFile = newFile;
    }

    @Override
    public void deleteEntriesFrom(long startIndexInclusive) throws IOException {
        long rollbackOffset = logEntryRingBuffer.deleteEntriesFrom(startIndexInclusive);
        logRaf.seek(rollbackOffset);
        logRaf.force();
    }

    @Override
    public void persistInitialMembers(
            RaftEndpoint localMember, Collection<RaftEndpoint> initialMembers
    ) throws IOException {

    }

    @Override
    public void persistTerm(int term, RaftEndpoint votedFor) throws IOException {

    }

    @Override
    public void flushLogs() throws IOException {
        flushCalledOnCurrFile = true;
        logRaf.force();

    }

    @Override
    public void close() throws IOException {

    }

    @Nonnull
    private static File fileWithIndex(long entryIndex) {
        return new File(String.format("raft-log-%016x.bin", entryIndex));
    }

    @Nonnull
    private BufferedRaf createFile(File file) throws IOException {
        return new BufferedRaf(new RandomAccessFile(file, "rw"));
    }

    private ObjectDataOutputStream newObjectDataOutput(BufferedRaf bufRaf) throws IOException {
        return bufRaf.asObjectDataOutputStream(getSerializationService());
    }

    // TODO: get serialization service from Hazelcast node
    private InternalSerializationService getSerializationService() {
        return new DefaultSerializationServiceBuilder().build();
    }
}