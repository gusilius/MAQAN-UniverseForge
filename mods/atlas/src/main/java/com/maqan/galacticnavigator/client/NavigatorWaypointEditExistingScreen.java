package com.maqan.galacticnavigator.client;

import com.maqan.galacticnavigator.navigation.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Edit existing waypoint metadata and coordinates without losing its identity. */
public final class NavigatorWaypointEditExistingScreen extends Screen {
    private final Screen parent; private final String id; private NavigatorWaypoint value;
    private EditBox name,x,y,z,group,note;
    public NavigatorWaypointEditExistingScreen(Screen parent,String id){
        super(Component.literal("MAQAN · Edit Waypoint"));this.parent=parent;this.id=id;
        value=NavigatorWaypointStore.all().stream().filter(w->w.id.equals(id)).findFirst().orElse(null);
    }
    @Override protected void init(){
        if(value==null){onClose();return;}
        int left=width/2-145,top=Math.max(48,height/2-100);
        name=field(left,top,290,"Name",value.name);
        x=field(left,top+27,92,"X",Double.toString(value.x));
        y=field(left+99,top+27,92,"Y",Double.toString(value.y));
        z=field(left+198,top+27,92,"Z",Double.toString(value.z));
        group=field(left,top+54,290,"Group",value.group);
        note=field(left,top+81,290,"Note",value.note);
        addRenderableWidget(Button.builder(Component.literal("SAVE"),b->save()).bounds(left,top+111,140,20).build());
        addRenderableWidget(Button.builder(Component.literal("CANCEL"),b->onClose()).bounds(left+150,top+111,140,20).build());
    }
    private EditBox field(int x,int y,int w,String hint,String v){
        EditBox e=new EditBox(font,x,y,w,20,Component.literal(hint));e.setHint(Component.literal(hint));e.setValue(v==null?"":v);addRenderableWidget(e);return e;
    }
    private void save(){
        try{
            value.name=name.getValue();value.x=Double.parseDouble(x.getValue());
            value.y=Double.parseDouble(y.getValue());value.z=Double.parseDouble(z.getValue());
            value.group=group.getValue();value.note=note.getValue();
            NavigatorWaypointStore.update(value);
            MaqanScreenHost.open(minecraft, new NavigatorWaypointManagerScreen(parent));
        }catch(Exception ex){minecraft.player.displayClientMessage(Component.literal("Invalid waypoint: "+ex.getMessage()),true);}
    }
    @Override public void onClose(){if(minecraft!=null)MaqanScreenHost.back(minecraft, parent);else super.onClose();}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){renderBackground(g);g.drawCenteredString(font,"EDIT WAYPOINT",width/2,Math.max(22,height/2-124),0xFF62D9F2);super.render(g,mx,my,partial);}
}
