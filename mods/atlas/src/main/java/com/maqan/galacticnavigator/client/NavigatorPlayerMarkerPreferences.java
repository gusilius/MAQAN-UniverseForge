package com.maqan.galacticnavigator.client;

import java.util.Locale;
import java.util.prefs.Preferences;

/** Persistent visual preferences for the player's own position marker in the atlas. */
public final class NavigatorPlayerMarkerPreferences {
    public enum Shape { SHIP, ARROW, TRIANGLE, DOT }
    public enum Tracking { FREE, FOLLOW, LOCK_HEADING }
    private static final Preferences STORE=Preferences.userNodeForPackage(NavigatorPlayerMarkerPreferences.class);
    private static boolean visible=STORE.getBoolean("player_marker_visible",true);
    private static boolean label=STORE.getBoolean("player_marker_label",true);
    private static boolean heading=STORE.getBoolean("player_marker_heading",true);
    private static boolean velocity=STORE.getBoolean("player_marker_velocity",false);
    private static boolean range=STORE.getBoolean("player_marker_range",true);
    private static Shape shape=parseShape(STORE.get("player_marker_shape","SHIP"));
    private static Tracking tracking=parseTracking(STORE.get("player_marker_tracking","FREE"));
    private static double scale=clamp(STORE.getDouble("player_marker_scale",1.0),0.5,3.0);
    private static int color=STORE.getInt("player_marker_color",0xFF55DDEE);
    private NavigatorPlayerMarkerPreferences() {}

    public static synchronized boolean visible(){return visible;}
    public static synchronized void setVisible(boolean v){visible=v;STORE.putBoolean("player_marker_visible",v);}
    public static synchronized boolean showLabel(){return label;}
    public static synchronized void setShowLabel(boolean v){label=v;STORE.putBoolean("player_marker_label",v);}
    public static synchronized boolean showHeading(){return heading;}
    public static synchronized void setShowHeading(boolean v){heading=v;STORE.putBoolean("player_marker_heading",v);}
    public static synchronized boolean showVelocity(){return velocity;}
    public static synchronized void setShowVelocity(boolean v){velocity=v;STORE.putBoolean("player_marker_velocity",v);}
    public static synchronized boolean showRange(){return range;}
    public static synchronized void setShowRange(boolean v){range=v;STORE.putBoolean("player_marker_range",v);}
    public static synchronized Shape shape(){return shape;}
    public static synchronized void setShape(Shape v){shape=v==null?Shape.SHIP:v;STORE.put("player_marker_shape",shape.name());}
    public static synchronized Tracking tracking(){return tracking;}
    public static synchronized void setTracking(Tracking v){tracking=v==null?Tracking.FREE:v;STORE.put("player_marker_tracking",tracking.name());}
    public static synchronized double scale(){return scale;}
    public static synchronized void setScale(double v){scale=clamp(v,0.5,3.0);STORE.putDouble("player_marker_scale",scale);}
    public static synchronized int color(){return color;}
    public static synchronized void setColor(int argb){color=argb;STORE.putInt("player_marker_color",argb);}
    public static synchronized String summary(){
        return String.format(Locale.ROOT,"%s · %s · %.1fx",shape.name(),tracking.name(),scale);
    }
    private static Shape parseShape(String v){try{return Shape.valueOf(v);}catch(Exception e){return Shape.SHIP;}}
    private static Tracking parseTracking(String v){try{return Tracking.valueOf(v);}catch(Exception e){return Tracking.FREE;}}
    private static double clamp(double v,double min,double max){return Math.max(min,Math.min(max,Double.isFinite(v)?v:min));}
}
