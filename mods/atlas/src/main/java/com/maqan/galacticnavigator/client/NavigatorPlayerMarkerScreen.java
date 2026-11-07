package com.maqan.galacticnavigator.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** In-game controls for the player's own marker in both local and system maps. */
public final class NavigatorPlayerMarkerScreen extends Screen {
    private final Screen parent;
    private static final int[] COLORS={0xFF55DDEE,0xFFFFC66D,0xFFFF718B,0xFF9D8BFF,0xFF8BE0A1,0xFFFFFFFF};
    public NavigatorPlayerMarkerScreen(Screen parent) {
        super(Component.literal("MAQAN · Player Marker")); this.parent=parent;
    }
    @Override protected void init() {
        int x=width/2-112,y=Math.max(24,height/2-120),w=224,row=22;
        addRenderableWidget(Button.builder(Component.literal("MARKER: "+onOff(NavigatorPlayerMarkerPreferences.visible())),b->{
            NavigatorPlayerMarkerPreferences.setVisible(!NavigatorPlayerMarkerPreferences.visible());
            refresh(b,"MARKER: "+onOff(NavigatorPlayerMarkerPreferences.visible()));
        }).bounds(x,y,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("STYLE: "+NavigatorPlayerMarkerPreferences.shape()),b->{
            NavigatorPlayerMarkerPreferences.Shape[] v=NavigatorPlayerMarkerPreferences.Shape.values();
            NavigatorPlayerMarkerPreferences.setShape(v[(NavigatorPlayerMarkerPreferences.shape().ordinal()+1)%v.length]);
            refresh(b,"STYLE: "+NavigatorPlayerMarkerPreferences.shape());
        }).bounds(x,y+row,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("CAMERA: "+NavigatorPlayerMarkerPreferences.tracking()),b->{
            NavigatorPlayerMarkerPreferences.Tracking[] v=NavigatorPlayerMarkerPreferences.Tracking.values();
            NavigatorPlayerMarkerPreferences.setTracking(v[(NavigatorPlayerMarkerPreferences.tracking().ordinal()+1)%v.length]);
            refresh(b,"CAMERA: "+NavigatorPlayerMarkerPreferences.tracking());
        }).bounds(x,y+row*2,w,20).build());
        addToggle(x,y+row*3,w,"LABEL",NavigatorPlayerMarkerPreferences.showLabel(),
                NavigatorPlayerMarkerPreferences::setShowLabel);
        addToggle(x,y+row*4,w,"HEADING",NavigatorPlayerMarkerPreferences.showHeading(),
                NavigatorPlayerMarkerPreferences::setShowHeading);
        addToggle(x,y+row*5,w,"VELOCITY VECTOR",NavigatorPlayerMarkerPreferences.showVelocity(),
                NavigatorPlayerMarkerPreferences::setShowVelocity);
        addToggle(x,y+row*6,w,"SPEED LABEL",NavigatorPlayerMarkerPreferences.showRange(),
                NavigatorPlayerMarkerPreferences::setShowRange);
        addRenderableWidget(Button.builder(Component.literal("SCALE: "+String.format(java.util.Locale.ROOT,"%.1fx",NavigatorPlayerMarkerPreferences.scale())),b->{
            double[] v={0.5,0.75,1.0,1.25,1.5,2.0,2.5,3.0};
            int current=0;for(int i=0;i<v.length;i++)if(Math.abs(v[i]-NavigatorPlayerMarkerPreferences.scale())<0.001)current=i;
            NavigatorPlayerMarkerPreferences.setScale(v[(current+1)%v.length]);
            refresh(b,"SCALE: "+String.format(java.util.Locale.ROOT,"%.1fx",NavigatorPlayerMarkerPreferences.scale()));
        }).bounds(x,y+row*7,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("COLOR: "+colorName(NavigatorPlayerMarkerPreferences.color())),b->{
            int current=0;for(int i=0;i<COLORS.length;i++)if(COLORS[i]==NavigatorPlayerMarkerPreferences.color())current=i;
            NavigatorPlayerMarkerPreferences.setColor(COLORS[(current+1)%COLORS.length]);
            refresh(b,"COLOR: "+colorName(NavigatorPlayerMarkerPreferences.color()));
        }).bounds(x,y+row*8,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("DONE"),b->onClose())
                .bounds(x,y+row*9+3,w,20).build());
    }
    private void addToggle(int x,int y,int w,String label,boolean initial,java.util.function.Consumer<Boolean> setter){
        Button button=addRenderableWidget(Button.builder(Component.literal(label+": "+onOff(initial)),b->{
            boolean next=!labelState(label);
            setter.accept(next);refresh(b,label+": "+onOff(next));
        }).bounds(x,y,w,20).build());
        // The persisted preference is the source of truth for each toggle.
    }
    private boolean labelState(String label){
        return switch(label){
            case "LABEL"->NavigatorPlayerMarkerPreferences.showLabel();
            case "HEADING"->NavigatorPlayerMarkerPreferences.showHeading();
            case "VELOCITY VECTOR"->NavigatorPlayerMarkerPreferences.showVelocity();
            case "SPEED LABEL"->NavigatorPlayerMarkerPreferences.showRange();
            default->false;
        };
    }
    private static String colorName(int color){
        for(int i=0;i<COLORS.length;i++)if(COLORS[i]==color)return new String[]{"CYAN","AMBER","CORAL","VIOLET","MINT","WHITE"}[i];
        return "CUSTOM";
    }
    private static String onOff(boolean value){return value?"ON":"OFF";}
    private static void refresh(Button button,String value){button.setMessage(Component.literal(value));}
    @Override public void onClose(){if(minecraft!=null)MaqanScreenHost.back(minecraft, parent);else super.onClose();}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        MaqanUiTheme.backdrop(g,width,height);
        g.drawCenteredString(font,"PLAYER POSITION MARKER",width/2,Math.max(8,height/2-144),0xFF65D8F2);
        g.drawCenteredString(font,"Shared by the local surface map and system atlas",width/2,height-14,0xFF9AABBE);
        super.render(g,mx,my,partial);MaqanUiTheme.skinWidgets(this,g,font);
    }
}
