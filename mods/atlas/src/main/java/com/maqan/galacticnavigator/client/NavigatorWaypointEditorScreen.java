package com.maqan.galacticnavigator.client;

import com.maqan.galacticnavigator.navigation.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Lightweight waypoint editor for manual coordinates or the verified player marker. */
public final class NavigatorWaypointEditorScreen extends Screen {
    private final Screen parent;
    private EditBox name, x, y, z, dimension, system, group, note;
    private boolean systemSpace;
    public NavigatorWaypointEditorScreen(Screen parent) {
        super(Component.literal("MAQAN · New Waypoint")); this.parent=parent;
        systemSpace=false;
    }
    @Override protected void init() {
        int left=width/2-150, top=Math.max(44,height/2-118), fieldW=300;
        NavigatorPlayerPosition local=NavigatorPlayerPositionTracker.current();
        name=field(left,top,fieldW,"Name","Waypoint");
        x=field(left,top+27,fieldW/3-4,"X",local==null?"0":Double.toString(local.x()));
        y=field(left+fieldW/3+2,top+27,fieldW/3-4,"Y",local==null?"0":Double.toString(local.y()));
        z=field(left+2*(fieldW/3+2),top+27,fieldW/3-4,"Z",local==null?"0":Double.toString(local.z()));
        dimension=field(left,top+54,fieldW,"Dimension ID",local==null?"minecraft:overworld":local.dimensionId());
        system=field(left,top+81,fieldW,"System ID",markerSystem());
        group=field(left,top+108,fieldW,"Group","Personal");
        note=field(left,top+135,fieldW,"Note (optional)","");
        addRenderableWidget(Button.builder(Component.literal("FRAME: "+(systemSpace?"SYSTEM SPACE":"DIMENSION")),
                b->{systemSpace=!systemSpace;b.setMessage(Component.literal("FRAME: "+(systemSpace?"SYSTEM SPACE":"DIMENSION")));})
                .bounds(left,top+164,fieldW,20).build());
        addRenderableWidget(Button.builder(Component.literal("USE MY CURRENT POSITION"),b->fillFromPlayer())
                .bounds(left,top+189,fieldW,20).build());
        addRenderableWidget(Button.builder(Component.literal("SAVE WAYPOINT"),b->saveWaypoint())
                .bounds(left,top+214,fieldW,20).build());
        addRenderableWidget(Button.builder(Component.literal("CANCEL"),b->onClose())
                .bounds(left,top+239,fieldW,20).build());
    }
    private EditBox field(int x,int y,int w,String hint,String value) {
        EditBox box=new EditBox(font,x,y,w,20,Component.literal(hint));
        box.setHint(Component.literal(hint)); box.setValue(value); box.setMaxLength(256);
        addRenderableWidget(box); return box;
    }
    private String markerSystem() {
        NavigatorPlayerMarker m=NavigatorPlayerMarkerTracker.current();
        return m==null?"":m.systemId();
    }
    private void fillFromPlayer() {
        NavigatorPlayerPosition local=NavigatorPlayerPositionTracker.current();
        if(local!=null){
            systemSpace=false;
            dimension.setValue(local.dimensionId());
            x.setValue(Double.toString(local.x()));
            y.setValue(Double.toString(local.y()));
            z.setValue(Double.toString(local.z()));
            return;
        }
        NavigatorPlayerMarker marker=NavigatorPlayerMarkerTracker.current();
        if(marker==null||!marker.positionKnown())return;
        systemSpace=true;system.setValue(marker.systemId());dimension.setValue(marker.dimensionId());
        x.setValue(Double.toString(marker.systemPosition().x()));
        y.setValue(Double.toString(marker.systemPosition().y()));
        z.setValue(Double.toString(marker.systemPosition().z()));
    }
    private void saveWaypoint() {
        try {
            double px=Double.parseDouble(x.getValue()),py=Double.parseDouble(y.getValue()),pz=Double.parseDouble(z.getValue());
            String dim=dimension.getValue().trim(),sys=system.getValue().trim();
            NavigatorWaypoint waypoint=new NavigatorWaypoint(name.getValue(),dim,sys,
                    systemSpace?NavigatorWaypoint.CoordinateFrame.SYSTEM_SPACE:NavigatorWaypoint.CoordinateFrame.DIMENSION,
                    NavigatorWaypoint.Scope.LOCAL,NavigatorWaypoint.Category.PERSONAL,
                    group.getValue(),"◆",0xFF62D9F2,px,py,pz,true,false,note.getValue());
            NavigatorWaypointStore.add(waypoint);
            if(minecraft!=null)MaqanScreenHost.open(minecraft, new NavigatorWaypointManagerScreen(parent));
        } catch(Exception exception) {
            if(minecraft!=null) minecraft.player.displayClientMessage(
                    Component.literal("Invalid waypoint: "+exception.getMessage()),true);
        }
    }
    @Override public void onClose(){if(minecraft!=null)MaqanScreenHost.back(minecraft, parent);else super.onClose();}
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        MaqanUiTheme.backdrop(g,width,height);
        g.drawCenteredString(font,"CREATE WAYPOINT",width/2,Math.max(22,height/2-142),0xFF62D9F2);
        super.render(g,mx,my,partial);MaqanUiTheme.skinWidgets(this,g,font);
    }
}
