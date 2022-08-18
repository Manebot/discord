package io.manebot.plugin.discord.platform.guild;

import io.manebot.chat.Chat;
import io.manebot.chat.Community;
import io.manebot.conversation.Conversation;
import io.manebot.platform.Platform;
import io.manebot.platform.PlatformUser;
import io.manebot.plugin.Plugin;
import io.manebot.plugin.PluginException;
import io.manebot.plugin.audio.Audio;
import io.manebot.plugin.audio.api.AudioConnection;
import io.manebot.plugin.audio.channel.AudioChannel;
import io.manebot.plugin.audio.channel.AudioChannelRegistrant;
import io.manebot.plugin.audio.mixer.Mixer;
import io.manebot.plugin.audio.opus.OpusParameters;
import io.manebot.plugin.audio.player.AudioPlayer;
import io.manebot.plugin.discord.platform.audio.DiscordAudioChannel;
import io.manebot.plugin.discord.platform.audio.DiscordMixerSink;
import io.manebot.plugin.discord.database.model.DiscordGuild;
import io.manebot.plugin.discord.platform.DiscordPlatformConnection;

import io.manebot.plugin.discord.platform.user.DiscordPlatformUser;
import io.manebot.user.User;
import io.manebot.user.UserAssociation;
import io.manebot.virtual.Virtual;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.CombinedAudio;
import net.dv8tion.jda.api.audio.OpusPacket;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.audio.hooks.ConnectionListener;
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ISnowflake;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.managers.AudioManager;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import java.util.stream.Collectors;

/**
 * Represents a connection to a specific authorized Guild in Discord.
 */
public class DiscordGuildConnection implements AudioChannelRegistrant, Community {
    private final Plugin plugin;
    private final DiscordGuild guildModel;
    private final Guild guild;
    private final DiscordPlatformConnection connection;
    private final Audio audio;
    private final io.manebot.plugin.audio.api.AudioConnection audioConnection;
    private final Object voiceConnectionLock = new Object();

    private final Object registerLock = new Object();
    private boolean registered = false;

    private Sleeper sleeper; // used to time the channel going to sleep
    private Mixer mixer;
    private DiscordMixerSink mixerSink;
    private DiscordAudioChannel channel;

    public DiscordGuildConnection(Plugin plugin,
                                  DiscordGuild guildModel,
                                  Guild guild,
                                  DiscordPlatformConnection platformConnection,
                                  Audio audio,
                                  AudioConnection audioConnection) {
        this.plugin = plugin;
        this.guildModel = guildModel;
        this.guild = guild;
        this.connection = platformConnection;

        this.audio = audio;
        this.audioConnection = audioConnection;
    }

    public DiscordGuild getModel() {
        return guildModel;
    }

    public Platform getPlatform() {
        return connection.getPlatform();
    }

    @Override
    public boolean isMember(User user) {
        return user.getAssociations(getPlatform()).stream()
                .map(UserAssociation::getPlatformUser)
                .anyMatch(this::isMember);
    }

    @Override
    public boolean isMember(PlatformUser user) {
        if (user instanceof DiscordPlatformUser) {
            return guild.isMember(((DiscordPlatformUser) user).getUser());
        } else {
            return false;
        }
    }

    @Override
    public Collection<String> getChatIds() {
        return guild.getChannels().stream()
                .filter(channel -> channel instanceof MessageChannel)
                .map(ISnowflake::getId).collect(Collectors.toList());
    }

    @Override
    public Collection<Chat> getChats() {
        return guild.getChannels().stream()
                .filter(channel -> channel instanceof MessageChannel)
                .map(channel -> getPlatformConnection().getChat((MessageChannel) channel))
                .collect(Collectors.toList());
    }

    @Override
    public Collection<String> getPlatformUserIds() {
        return guild.getMembers().stream().map(member -> member.getUser().getId()).collect(Collectors.toList());
    }

    @Override
    public Collection<PlatformUser> getPlatformUsers() {
        return guild.getMembers().stream()
                .map(member -> getPlatformConnection().getPlatformUser(member.getUser()))
                .collect(Collectors.toList());
    }

    @Override
    public Chat getDefaultChat() {
        TextChannel defaultTextChannel = guild.getDefaultChannel();
        if (defaultTextChannel == null) return null;
        return getPlatformConnection().getChat(defaultTextChannel);
    }

    @Override
    public boolean isConnected() {
        return guild.isAvailable();
    }

    public String getId() {
        return guild.getId();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    public void setName(String name) throws UnsupportedOperationException {
        guild.getManager().setName(name);
    }

    public DiscordAudioChannel getAudioChannel() {
        return channel;
    }

    public DiscordMixerSink getMixerSink() {
        return mixerSink;
    }

    public DiscordPlatformConnection getPlatformConnection() {
        return connection;
    }

    public Guild getGuild() {
        return guild;
    }

    public boolean isRegistered() {
        return registered;
    }

    public void unregisterAudio() {
        // Deconstruct audio system
        if (channel != null) {
            channel.disconnect();
            audioConnection.unregisterChannel(channel);
            channel = null;
        }

        if (mixer != null) {
            mixer.empty();
            mixer.setRunning(false);
            audioConnection.unregisterMixer(mixer);
            mixer = null;
        }
    }

    public void registerAudio() throws PluginException {
        plugin.getLogger().fine("Registering audio mixer for guild \"" + guild.getName()
                + "\" [" + getId() + "] ...");

        // Deconstruct audio system
        unregisterAudio();

        // Create mixer around the sink and audio channel around the mixer
        if (audio != null) {
            mixer = audioConnection.registerMixer(audio.createMixer(getId(), consumer -> {
                consumer.setFormat(48000f, 2);
                consumer.addDefaultFilters();
            }));

            mixer.addSink(mixerSink = new DiscordMixerSink(
                    DiscordMixerSink.AUDIO_FORMAT,
                    OpusParameters.fromPluginConfiguration(plugin),
                    mixer.getBufferSize() * (DiscordMixerSink.AUDIO_FORMAT.getSampleSizeInBits()/8)
            ));

            audioConnection.registerChannel(channel = new DiscordAudioChannel(this, mixer, this));

            AudioManager audioManager = guild.getAudioManager();

            audioManager.setSendingHandler(mixerSink);

            audioManager.setConnectionListener(new ConnectionListener() {
                @Override
                public void onPing(long ping) {

                }

                @Override
                public void onStatusChange(ConnectionStatus connectionStatus) {

                }

                @Override
                public void onUserSpeaking(net.dv8tion.jda.api.entities.User user, boolean speaking) {
                    //TODO
                }
            });

            audioManager.setReceivingHandler(new AudioReceiveHandler() {
                @Override
                public boolean canReceiveCombined() {
                    return false;
                }

                @Override
                public void handleCombinedAudio(CombinedAudio combinedAudio) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean canReceiveUser() {
                    return false;
                }

                @Override
                public boolean canReceiveEncoded() {
                    return true;
                }

                @Override
                public void handleEncodedAudio(OpusPacket opusPacket) {

                }
            });

            channel.setIdle(true);

            plugin.getLogger().fine("Registered audio mixer for guild \"" + guild.getName()
                    + "\" [" + getId() + "].");
        } else
            plugin.getLogger().warning("Couldn't register audio for guild ["
                    + getId() + "] because audio was not initialized.");
    }

    public void register() throws Exception {
        synchronized (registerLock) {
            if (registered) return;

            plugin.getLogger().fine("Connecting to guild \"" + guild.getName()
                    + "\" [" + getId() + "] ...");

            // Initialize audio subsystem for this guild.
            if (guildModel.isMusicEnabled()) {
                registerAudio();
            } else {
                channel = null;
            }

            this.registered = true;

            plugin.getLogger().info("Connected to guild \"" + guild.getName()
                    + "\" [" + getId() + "].");
        }
    }

    public void unregister() {
        synchronized (registerLock) {
            if (!registered) return;

            try {
                plugin.getLogger().info("Disconnecting from guild \"" + guild.getName()
                        + "\" [" + getId() + "] ...");


            } finally {
                this.registered = false;
            }

            plugin.getLogger().info("Disconnected from guild \"" + guild.getName()
                    + "\" [" + getId() + "].");
        }
    }

    public void onJoin() {
        synchronized (voiceConnectionLock) {
            voiceConnectionLock.notifyAll();
        }
    }

    private Sleeper schedule() {
        int timeout = guildModel.getIdleTimeout();

        if (timeout <= 0) {
            return null;
        }

        return schedule(timeout * 1000);
    }

    private Sleeper schedule(long millis) {
        if (sleeper != null && !sleeper.isDone()) {
            sleeper.cancel();
        }

        Virtual.getInstance()
                .create(sleeper = new Sleeper(System.currentTimeMillis() + millis))
                .start();

        return sleeper;
    }

    public Conversation getDefaultConversation() {
        return guildModel.getDefaultConversation();
    }

    public List<User> getListeners() {
        return Collections.unmodifiableList(channel
                .getRegisteredListeners().stream()
                .map(UserAssociation::getUser)
                .collect(Collectors.toList())
        );
    }

    // JBot audio system events ========================================================================================

    @Override
    public void onPlayerStarted(AudioChannel audioChannel, AudioPlayer audioPlayer) {
        // Do nothing
    }

    @Override
    public void onPlayerStopped(AudioChannel audioChannel, AudioPlayer audioPlayer) {
        // Do nothing
    }

    @Override
    public void onChannelActivated(AudioChannel audioChannel) {
        // Do nothing
    }

    @Override
    public void onChannelPassivated(AudioChannel audioChannel) {
        schedule();
    }

    @Override
    public void onChannelSleep(AudioChannel channel) {
        this.channel.disconnect();
    }

    @Override
    public void onChannelWake(AudioChannel channel) {
        schedule();
    }

    private class Sleeper implements Runnable {
        private final long sleepTime;
        private final Object lock = new Object();
        private volatile boolean cancel = false;
        private volatile boolean done = false;

        private Sleeper(long sleepTime) {
            this.sleepTime = sleepTime;
        }

        @Override
        public void run() {
            synchronized (lock) {
                while (!cancel) {
                    try {
                        lock.wait(Math.max(0, sleepTime - System.currentTimeMillis()));
                        break;
                    } catch (InterruptedException e) {
                        Thread.yield();
                    }
                }

                if (cancel) {
                    done = true;
                    return;
                }

                if (channel != null && channel.getState() == AudioChannel.State.WAITING)
                    channel.setIdle(true);

                sleeper = null;

                done = true;
            }
        }

        void cancel() {
            synchronized (lock) {
                cancel = true;
                lock.notifyAll();
            }
        }

        boolean isDone() {
            return done;
        }
    }
}
