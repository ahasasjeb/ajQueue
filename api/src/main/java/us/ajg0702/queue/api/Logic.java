package us.ajg0702.queue.api;

import us.ajg0702.queue.api.players.AdaptedPlayer;
import us.ajg0702.queue.api.players.QueuePlayer;

import java.util.List;

public interface Logic {
    /**
     * Returns if the plugin is premium or not
     * @return True if premium, false if not
     */
    boolean isPremium();

    /**
     * The priority logic that is executed if the plugin is premium.
     * @param list The current queue
     * @param server The server/group name that is being queued for
     * @param player The player that is being queued
     */
    void priorityLogic(List<QueuePlayer> list, String server, AdaptedPlayer player);
}
