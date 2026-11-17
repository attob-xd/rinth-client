package rinth.app.desktop.smoke;

import java.util.List;
import rinth.platform.api.net.NetConnection;
import rinth.platform.api.rtc.IceServer;
import rinth.platform.api.rtc.Microphone;
import rinth.platform.api.rtc.PlatformRtc;
import rinth.platform.api.rtc.RemoteVoice;
import rinth.platform.api.rtc.RtcPeer;

/**
 * WebRTC's stand-in on the desktop, for the voice smoke test: peers exchange made-up
 * descriptions and candidates, carried by the real server's signalling, and report the peer's
 * audio once both descriptions and a candidate from the far side have come through. No audio flows;
 * the browser checks cover that. Listener calls come on the game's thread, as in the browser.
 */
final class ScriptedRtc implements PlatformRtc {

    private boolean micOpen;

    @Override
    public boolean supported() {
        return true;
    }

    /** As the browser's callbacks do: later, on the game's thread. */
    private static void later(Runnable action) {
        net.minecraft.client.Minecraft.getInstance().execute(action);
    }

    @Override
    public Microphone microphone() {
        return new Microphone() {
            @Override
            public State state() {
                return micOpen ? State.OPEN : State.CLOSED;
            }

            @Override
            public void open() {
                micOpen = true;
            }

            @Override
            public void close() {
                micOpen = false;
            }

            @Override
            public void setTransmitting(boolean transmitting) {}

            @Override
            public void setVolume(float volume) {}

            @Override
            public float level() {
                return 0;
            }
        };
    }

    @Override
    public RtcPeer createPeer(List<IceServer> iceServers, RtcPeer.Listener listener) {
        return new RtcPeer() {
            private boolean local;
            private boolean remote;
            private boolean candidate;
            private boolean audio;
            private boolean closed;

            @Override
            public void sendMicrophone() {}

            @Override
            public NetConnection openDataChannel(String label, NetConnection.Listener channel) {
                throw new UnsupportedOperationException("Voice only");
            }

            @Override
            public void createOffer() {
                local = true;
                later(() -> {
                    listener.localDescription("{\"type\":\"offer\",\"sdp\":\"scripted\"}");
                    listener.iceCandidate("candidate:1 1 udp 1 127.0.0.1 9 typ host", 0);
                });
            }

            @Override
            public void setRemoteDescription(String json) {
                remote = true;
                if (json.contains("offer")) {
                    local = true;
                    later(() -> {
                        listener.localDescription("{\"type\":\"answer\",\"sdp\":\"scripted\"}");
                        listener.iceCandidate("candidate:2 1 udp 1 127.0.0.1 9 typ host", 0);
                    });
                }
                check();
            }

            @Override
            public void addIceCandidate(String line, int sdpMLineIndex) {
                candidate = true;
                check();
            }

            private void check() {
                if (local && remote && candidate && !audio && !closed) {
                    audio = true;
                    later(() -> {
                        listener.stateChanged("connected");
                        listener.remoteAudio();
                    });
                }
            }

            @Override
            public RemoteVoice voice() {
                return new RemoteVoice() {
                    @Override
                    public void setVolume(float volume) {}

                    @Override
                    public void setPan(float pan) {}

                    @Override
                    public float level() {
                        return 0;
                    }
                };
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }
}
