package com.maqan.galacticnavigator.navigation;

/**
 * Runtime bridge for the player's location in system-space coordinates.
 * The integration layer must publish only after applying a verified
 * dimension-to-system transform. Unknown coordinates are never guessed.
 */
public final class NavigatorPlayerMarkerTracker {
    private static volatile NavigatorPlayerMarker current;
    private NavigatorPlayerMarkerTracker() {}
    public static NavigatorPlayerMarker current(){return current;}
    public static void publish(NavigatorPlayerMarker marker){current=marker;}
    public static void clear(){current=null;}
}
