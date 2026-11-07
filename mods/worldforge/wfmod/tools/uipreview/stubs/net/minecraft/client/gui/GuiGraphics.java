package net.minecraft.client.gui; import net.minecraft.resources.ResourceLocation; import net.minecraft.network.chat.Component; import java.util.List; import java.awt.*; import java.awt.image.BufferedImage; import com.mojang.blaze3d.vertex.PoseStack;
public class GuiGraphics { public final BufferedImage img; public final Graphics2D g; final PoseStack ps=new PoseStack();
 public GuiGraphics(int w,int h){ img=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB); g=img.createGraphics(); g.setFont(Font.F); ps.g=g; g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);} 
 public void enableScissor(int a,int b,int c,int d){ g.setClip(a,b,c-a,d-b); }
 public void disableScissor(){ g.setClip(null); }
 public PoseStack pose(){return ps;}
 public void fill(int a,int b,int c,int d,int col){ g.setColor(new Color(col,true)); g.fillRect(Math.min(a,c),Math.min(b,d),Math.abs(c-a),Math.abs(d-b)); }
 public void blit(ResourceLocation r,int x,int y,float u,float v,int w,int h,int tw,int th){ BufferedImage t=net.minecraft.client.renderer.texture.TextureManager.TEX.get(r.toString()); if(t==null) return; g.drawImage(t,x,y,x+w,y+h,(int)u,(int)v,(int)u+w,(int)v+h,null); }
 public int drawString(Font f,String s,int x,int y,int c){ g.setColor(new Color(0,0,0,255)); g.drawString(s,x+1,y+8); g.setColor(new Color(c,true)); g.drawString(s,x,y+7); return 0; }
 public int drawString(Font f,Component s,int x,int y,int c){ return drawString(f,s.getString(),x,y,c); }
 public int drawString(Font f,net.minecraft.util.FormattedCharSequence s,int x,int y,int c){ return drawString(f,s.s,x,y,c); }
 public void drawCenteredString(Font f,String s,int x,int y,int c){ drawString(f,s,x-f.width(s)/2,y,c); }
 public void renderOutline(int x,int y,int w,int h,int c){ fill(x,y,x+w,y+1,c); fill(x,y+h-1,x+w,y+h,c); fill(x,y,x+1,y+h,c); fill(x+w-1,y,x+w,y+h,c); }
 public void renderTooltip(Font f,Component c,int x,int y){ renderComponentTooltip(f,List.of(c),x,y); }
 public void renderComponentTooltip(Font f,List<Component> l,int x,int y){ int w=0; for(Component c:l) w=Math.max(w,f.width(c)); int h=l.size()*10+6; x+=12; y-=12; fill(x-3,y-3,x+w+3,y+h-3,0xF0100010); renderOutline(x-3,y-3,w+6,h,0xFF2A0A60); int yy=y; for(Component c:l){ drawString(f,c,x,yy,0xFFFFFFFF); yy+=10; } } }