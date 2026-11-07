package com.maqan.galacticnavigator.navigation;

/** Volatile latest local player snapshot, refreshed by client tick events. */
public final class NavigatorPlayerPositionTracker {
    private static volatile NavigatorPlayerPosition current;
    private NavigatorPlayerPositionTracker(){}
    public static NavigatorPlayerPosition current(){return current;}
    public static void publish(NavigatorPlayerPosition value){current=value;}
    public static void clear(){current=null;}
}
