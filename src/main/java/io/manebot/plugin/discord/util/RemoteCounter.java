package io.manebot.plugin.discord.util;

public interface RemoteCounter {
        int getGeneration(int var1);

        int getCurrentGeneration();

        int getCurrentPacketId();

        boolean put(int var1);

        void reset();
    }