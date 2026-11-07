package com.maqan.galacticnavigator.client;

import com.maqan.galacticnavigator.navigation.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Waypoint groups, visibility toggles and quick navigation target management. */
public final class NavigatorWaypointManagerScreen extends Screen {
    private final Screen parent;
    private int scroll;
    public NavigatorWaypointManagerScreen(Screen parent) {
        super(Component.literal("MAQAN · Waypoints")); this.parent=parent;
    }
    @Override protected void init() {
        int left=width/2-145;
        addRenderableWidget(Button.builder(Component.literal("NEW WAYPOINT"),
                b->MaqanScreenHost.open(minecraft, new NavigatorWaypointEditorScreen(this)))
                .bounds(left,36,140,20).build());
        addRenderableWidget(Button.builder(Component.literal("CLEAR TEMPORARY"),
                b->{NavigatorWaypointStore.clearTemporary();rebuildWidgets();})
                .bounds(left+146,36,144,20).build());
        addRenderableWidget(Button.builder(Component.literal("DONE"),b->onClose())
                .bounds(width/2-60,height-30,120,20).build());
        List<NavigatorWaypoint> all=NavigatorWaypointStore.all();
        int top=66, rowH=27, visibleRows=Math.max(1,(height-110)/rowH);
        int maxScroll=Math.max(0,all.size()-visibleRows);
        scroll=Math.max(0,Math.min(scroll,maxScroll));
        for(int i=scroll;i<Math.min(all.size(),scroll+visibleRows);i++) {
            NavigatorWaypoint w=all.get(i);
            int row=i-scroll, y=top+row*rowH;
            int index=i;
            addRenderableWidget(Button.builder(Component.literal((w.visible?"● ":"○ ")+shortName(w.name)),
                    b->{NavigatorWaypoint fresh=find(w.id);if(fresh!=null){fresh.visible=!fresh.visible;NavigatorWaypointStore.update(fresh);}rebuildWidgets();})
                    .bounds(left,y,166,21).build());
            addRenderableWidget(Button.builder(Component.literal("EDIT"),
                    b->MaqanScreenHost.open(minecraft, new NavigatorWaypointEditExistingScreen(this,w.id)))
                    .bounds(left+171,y,54,21).build());
            addRenderableWidget(Button.builder(Component.literal("×"),
                    b->{NavigatorWaypointStore.remove(w.id);rebuildWidgets();})
                    .bounds(left+230,y,30,21).build());
        }
        addRenderableWidget(Button.builder(Component.literal("↑"),b->{scroll=Math.max(0,scroll-1);rebuildWidgets();})
                .bounds(width/2+150,66,22,20).build());
        addRenderableWidget(Button.builder(Component.literal("↓"),b->{scroll++;rebuildWidgets();})
                .bounds(width/2+150,90,22,20).build());
    }
    private static NavigatorWaypoint find(String id){return NavigatorWaypointStore.all().stream().filter(w->w.id.equals(id)).findFirst().orElse(null);}
    private static String shortName(String value){return value.length()>20?value.substring(0,19)+"…":value;}
    @Override public void onClose(){if(minecraft!=null)MaqanScreenHost.back(minecraft, parent);else super.onClose();}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        MaqanUiTheme.backdrop(g,width,height);g.drawCenteredString(font,"WAYPOINT MANAGER",width/2,15,0xFF62D9F2);
        List<NavigatorWaypoint> all=NavigatorWaypointStore.all();
        g.drawCenteredString(font,all.size()+" saved locations",width/2,Math.max(55,height-54),0xFF9AAFC2);
        super.render(g,mx,my,partial);MaqanUiTheme.skinWidgets(this,g,font);
    }
}
