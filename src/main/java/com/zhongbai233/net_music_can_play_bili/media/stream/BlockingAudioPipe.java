package com.zhongbai233.net_music_can_play_bili.media.stream;

import java.io.IOException;
import java.io.InputStream;

public final class BlockingAudioPipe extends InputStream {
    private static final int DEFAULT_MAX_CAPACITY = 32 * 1024 * 1024;
    /**
     * 写端在缓冲区满时允许等待读端消费的最长秒数。
     *
     * <p>原先写端用无参 {@code wait()} 无限等待，而退出条件只看 {@code readerClosed}，后者只在
     * {@link #close()} 里置位（{@link #closeWriter()} 置的是另一个标志）。一旦读端线程因解码异常
     * 退出而调用方没在 finally 中 close()，写端会永久挂在 wait() 上且无任何诊断。这里改成有界等待：
     * 缓冲持续满 30 秒即判定读端已死并抛出 IOException，正常播放时读端持续消费，不会触发。</p>
     */
    private static final int WRITER_STALL_TIMEOUT_SECONDS = 30;

    private final int initialCapacity;
    private final int maxCapacity;
    private byte[] buffer;
    private int readPos;
    private int writePos;
    private int size;
    private boolean readerClosed;
    private boolean writerClosed;

    public BlockingAudioPipe(int capacity) {
        this(capacity, DEFAULT_MAX_CAPACITY);
    }

    public BlockingAudioPipe(int capacity, int maxCapacity) {
        this.initialCapacity = Math.max(4096, capacity);
        this.maxCapacity = Math.max(this.initialCapacity, maxCapacity);
        this.buffer = new byte[this.initialCapacity];
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException("buffer");
        }
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }

        while (size == 0 && !writerClosed && !readerClosed) {
            waitForPipe();
        }
        if (size == 0 && writerClosed) {
            return -1;
        }
        if (readerClosed) {
            return -1;
        }

        int n = Math.min(len, size);
        int first = Math.min(n, buffer.length - readPos);
        System.arraycopy(buffer, readPos, b, off, first);
        int second = n - first;
        if (second > 0) {
            System.arraycopy(buffer, 0, b, off + first, second);
        }
        readPos = (readPos + n) % buffer.length;
        size -= n;
        if (size == 0 && buffer.length > initialCapacity * 8) {
            buffer = new byte[initialCapacity * 2];
            readPos = 0;
            writePos = 0;
        }
        notifyAll();
        return n;
    }

    public synchronized void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    public synchronized void write(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException("buffer");
        }
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }

        int written = 0;
        int stalledSeconds = 0;
        while (written < len) {
            while (size == buffer.length && !readerClosed && buffer.length >= maxCapacity) {
                waitForPipe(1_000L);
                if (++stalledSeconds > WRITER_STALL_TIMEOUT_SECONDS) {
                    throw new IOException("audio pipe writer stalled: reader not consuming for "
                            + WRITER_STALL_TIMEOUT_SECONDS + "s");
                }
            }
            stalledSeconds = 0;
            if (readerClosed) {
                throw new IOException("audio pipe reader closed");
            }
            if (size == buffer.length) {
                grow();
            }

            int available = buffer.length - size;
            int n = Math.min(len - written, available);
            int first = Math.min(n, buffer.length - writePos);
            System.arraycopy(b, off + written, buffer, writePos, first);
            int second = n - first;
            if (second > 0) {
                System.arraycopy(b, off + written + first, buffer, 0, second);
            }
            writePos = (writePos + n) % buffer.length;
            size += n;
            written += n;
            notifyAll();
        }
    }

    public synchronized void closeWriter() {
        writerClosed = true;
        notifyAll();
    }

    @Override
    public synchronized void close() {
        readerClosed = true;
        notifyAll();
    }

    private void grow() {
        int newCapacity = Math.min(buffer.length * 2, maxCapacity);
        if (newCapacity <= buffer.length) {
            return;
        }
        byte[] newBuffer = new byte[newCapacity];
        int first = Math.min(size, buffer.length - readPos);
        System.arraycopy(buffer, readPos, newBuffer, 0, first);
        if (size > first) {
            System.arraycopy(buffer, 0, newBuffer, first, size - first);
        }
        buffer = newBuffer;
        readPos = 0;
        writePos = size;
    }

    private void waitForPipe() throws IOException {
        waitForPipe(0L);
    }

    private void waitForPipe(long timeoutMillis) throws IOException {
        try {
            if (timeoutMillis > 0L) {
                wait(timeoutMillis);
            } else {
                wait();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("audio pipe interrupted", e);
        }
    }
}
