package rinth.framework.testing;

import rinth.framework.online.RinthPlayer;
import rinth.framework.online.Online;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** An {@link Online} a test sets by hand: signed in or not, and who in the world is an Rinth player. */
public final class FakeOnline implements Online {

    private Status status = Status.SIGNED_OUT;
    private @Nullable String accountName;
    private final Map<UUID, RinthPlayer> players = new HashMap<>();
    private long revision;

    /** Signs in as {@code name}. */
    public FakeOnline signIn(String name) {
        status = Status.SIGNED_IN;
        accountName = name;
        revision++;
        return this;
    }

    /** Signs out, forgetting the players. */
    public FakeOnline signOut() {
        status = Status.SIGNED_OUT;
        accountName = null;
        players.clear();
        revision++;
        return this;
    }

    /** Makes {@code id} an Rinth player in the world, or not ({@code player} null). */
    public FakeOnline player(UUID id, @Nullable RinthPlayer player) {
        if (player == null) {
            players.remove(id);
        } else {
            players.put(id, player);
        }
        revision++;
        return this;
    }

    @Override
    public Status status() {
        return status;
    }

    @Override
    public @Nullable String accountName() {
        return accountName;
    }

    @Override
    public @Nullable RinthPlayer player(UUID id) {
        return players.get(id);
    }

    @Override
    public long revision() {
        return revision;
    }
}
