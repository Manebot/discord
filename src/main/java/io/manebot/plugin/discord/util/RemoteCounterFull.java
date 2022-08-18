package io.manebot.plugin.discord.util;

import io.manebot.tuple.Pair;

public class RemoteCounterFull implements RemoteCounter {
    private final Integer[] buffer;
    private final int bufferSize;
    private final int generationSize;
    private Pair<Integer, Integer> bufferStart = new Pair(0, 0);
    private Pair<Integer, Integer> bufferEnd;
    private int latestPacketId = 0;

    public RemoteCounterFull(int generationSize, int windowSize) {
        if (windowSize >= generationSize) {
            throw new IllegalArgumentException("windowSize > generationSize");
        } else {
            this.buffer = new Integer[windowSize];
            this.bufferSize = windowSize;
            this.generationSize = generationSize;
            this.bufferEnd = new Pair(0, windowSize - 1);
        }
    }

    public Pair<Integer, Integer> getBufferStart() {
        return this.bufferStart;
    }

    public Pair<Integer, Integer> getBufferEnd() {
        return this.bufferEnd;
    }

    public int getGeneration(int packetId) {
        Pair bufferStartCopy;
        Pair bufferEndCopy;
        synchronized(this) {
            bufferStartCopy = this.bufferStart;
            bufferEndCopy = this.bufferEnd;
        }

        packetId %= this.generationSize;
        if (packetId >= (Integer)bufferStartCopy.getRight() && packetId < (Integer)bufferStartCopy.getRight() + this.bufferSize && packetId < this.generationSize) {
            return (Integer)bufferStartCopy.getLeft();
        } else if (packetId <= (Integer)bufferEndCopy.getRight() && packetId > 0 && packetId > (Integer)bufferEndCopy.getRight() - this.bufferSize && packetId < this.generationSize) {
            return (Integer)bufferEndCopy.getLeft();
        } else if (packetId > (Integer)bufferEndCopy.getRight()) {
            return (Integer)bufferEndCopy.getLeft();
        } else if (packetId < (Integer)bufferStartCopy.getRight()) {
            return (Integer)bufferEndCopy.getLeft() + 1;
        } else {
            throw new IllegalStateException();
        }
    }

    public int getCurrentGeneration() {
        return (Integer)this.bufferEnd.getLeft();
    }

    public int getCurrentPacketId() {
        return this.latestPacketId;
    }

    public boolean put(int packetId) {
        synchronized(this) {
            if (packetId >= (Integer)this.bufferStart.getRight() && packetId < (Integer)this.bufferStart.getRight() + this.bufferSize && packetId < this.generationSize) {
                this.latestPacketId = Math.max(this.latestPacketId, packetId);
                return this.putRelative(packetId - (Integer)this.bufferStart.getRight(), (Integer)this.bufferStart.getLeft());
            } else if (packetId <= (Integer)this.bufferEnd.getRight() && packetId >= 0 && packetId > (Integer)this.bufferEnd.getRight() - this.bufferSize && packetId < this.generationSize) {
                this.latestPacketId = Math.max(this.latestPacketId, packetId);
                return this.putRelative(this.bufferSize - ((Integer)this.bufferEnd.getRight() - packetId) - 1, (Integer)this.bufferEnd.getLeft());
            } else {
                int amountMoved = 0;
                if (packetId > (Integer)this.bufferEnd.getRight()) {
                    amountMoved = packetId - (Integer)this.bufferEnd.getRight();
                }

                if (packetId < (Integer)this.bufferStart.getRight()) {
                    amountMoved = this.generationSize - (Integer)this.bufferStart.getRight() + packetId + 1;
                    amountMoved -= this.bufferSize;
                }

                if (amountMoved <= 0) {
                    throw new IllegalStateException();
                } else {
                    int toMove = Math.max(0, this.bufferSize - amountMoved);
                    if (toMove > 0 && toMove < this.bufferSize) {
                        System.arraycopy(this.buffer, amountMoved, this.buffer, 0, toMove);
                    }

                    int toNullify = Math.max(0, Math.min(this.bufferSize, amountMoved));

                    int bufferStartGeneration;
                    for(bufferStartGeneration = this.bufferSize - toNullify; bufferStartGeneration < this.bufferSize; ++bufferStartGeneration) {
                        this.buffer[bufferStartGeneration] = null;
                    }

                    bufferStartGeneration = (Integer)this.bufferStart.getLeft();
                    int bufferStartPosition = (Integer)this.bufferStart.getRight();
                    bufferStartPosition += amountMoved;
                    if (bufferStartPosition >= this.generationSize) {
                        bufferStartPosition %= this.generationSize;
                        ++bufferStartGeneration;
                    }

                    int bufferEndPosition = (bufferStartPosition + this.bufferSize - 1) % this.generationSize;
                    int bufferEndGeneration;
                    if (bufferEndPosition < bufferStartPosition) {
                        bufferEndGeneration = bufferStartGeneration + 1;
                    } else {
                        bufferEndGeneration = bufferStartGeneration;
                    }

                    this.bufferStart = new Pair(bufferStartGeneration, bufferStartPosition);
                    this.bufferEnd = new Pair(bufferEndGeneration, bufferEndPosition);
                    this.latestPacketId = packetId;
                    return this.put(packetId);
                }
            }
        }
    }

    public void reset() {
        synchronized(this) {
            for(int i = 0; i < this.bufferSize; ++i) {
                this.buffer[i] = null;
            }

            this.bufferStart = new Pair(0, 0);
            this.bufferEnd = new Pair(0, this.bufferSize - 1);
            this.latestPacketId = 0;
        }
    }

    private boolean putRelative(int index, int generation) {
        synchronized(this) {
            Integer existing = this.buffer[index];
            if (existing != null && existing == generation) {
                return false;
            } else {
                this.buffer[index] = generation;
                return true;
            }
        }
    }
}

 