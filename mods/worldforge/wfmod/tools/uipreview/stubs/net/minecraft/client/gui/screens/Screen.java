package net.minecraft.client.gui.screens; import net.minecraft.network.chat.Component; import net.minecraft.client.gui.*;
public class Screen { public int width,height; public Font font; protected Screen(Component c){} protected void init(){} public void render(GuiGraphics g,int a,int b,float c){}
 public boolean isPauseScreen(){return true;} public void removed(){} public void tick(){} public void onClose(){} public static boolean hasShiftDown(){return false;}
 protected <T> T addRenderableWidget(T t){return t;} protected void setFocused(Object o){}
 public boolean mouseClicked(double a,double b,int c){return false;} public boolean mouseDragged(double a,double b,int c,double d,double e){return false;}
 public boolean mouseReleased(double a,double b,int c){return false;} public boolean mouseScrolled(double a,double b,double c){return false;} public boolean keyPressed(int a,int b,int c){return false;} }