package dev.maqan.platform.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;

/**
 * Data-free visual preview of MAQAN workspaces.
 *
 * This renderer deliberately draws only schematic geometry and type markers.
 * It never invents catalog entries, coordinates, political entities or game state.
 */
final class WorkspacePreviewRenderer {
    private static final int TEXT = 0xFFE5EEF6;
    private static final int MUTED = 0xFF7890A5;
    private static final int CYAN = 0xFF69CDE2;
    private static final int GRID = 0x182C5368;
    private WorkspacePreviewRenderer() {}

    static void render(GuiGraphics g, Font font, int width, int height,
                       String mode, int layer, int mouseX, int mouseY, boolean gridVisible) {
        if (gridVisible) drawGrid(g, width, height);
        if ("COSMOS".equals(mode)) drawCosmos(g, font, width, height, layer, mouseX, mouseY);
        else if ("POLITICS".equals(mode)) drawPolitics(g, font, width, height, mouseX, mouseY);
        else if ("SETTLEMENTS".equals(mode)) drawSettlements(g, font, width, height, mouseX, mouseY);
        else drawRts(g, font, width, height, mouseX, mouseY);
    }

    private static void drawGrid(GuiGraphics g, int w, int h) {
        for (int x=56; x<w; x+=48) g.fill(x,36,x+1,h,GRID);
        for (int y=60; y<h; y+=48) g.fill(44,y,w,y+1,GRID);
        for (int i=0;i<70;i++) {
            int x=52+(i*137)%(Math.max(1,w-64));
            int y=42+(i*83)%(Math.max(1,h-48));
            g.fill(x,y,x+1,y+1,0x553A647C);
        }
    }

    private static void drawCosmos(GuiGraphics g, Font f, int w, int h, int layer, int mx, int my) {
        String[] labels={"UNIVERSE","GALAXY","SECTOR","SYSTEM"};
        String[] icons={"◎","✧","▦","◌"};
        int start=58;
        for(int i=0;i<4;i++) {
            int x=start+i*52;
            boolean active=i==layer;
            g.fill(x,42,x+44,64,active?0x663B9FB5:0x44101924);
            if(active) g.fill(x,63,x+44,65,CYAN);
            g.drawCenteredString(f,icons[i],x+22,47,active?CYAN:MUTED);
            if(mx>=x&&mx<x+44&&my>=42&&my<65)
                g.renderTooltip(net.minecraft.client.Minecraft.getInstance().font,
                        net.minecraft.network.chat.Component.literal(labels[i]),mx,my);
        }
        g.drawString(f,"SCHEMATIC  /  CATALOG NOT CONNECTED",Math.max(220,w-250),50,MUTED,false);

        int cx=Math.max(250,w/2+20), cy=Math.max(180,(h+30)/2);
        int rx=Math.max(90,Math.min(270,(w-170)/3));
        int ry=Math.max(54,Math.min(170,(h-130)/3));
        for(int ring=0;ring<3;ring++) ellipse(g,cx,cy,rx*(0.45+ring*0.27),ry*(0.45+ring*0.27),0x553A91A9);
        if(layer==0) {
            cube(g,cx-5,cy-5,10,0xFF69CDE2);
            bodyLabel(g,f,cx+17,cy-5,"GALAXY",CYAN);
            cube(g,cx-rx/2,cy-ry/3,9,0xFF7897B2);
            cube(g,cx+rx/2,cy+ry/4,9,0xFF7897B2);
            cube(g,cx-rx/3,cy+ry/2,8,0xFF7897B2);
            cube(g,cx+rx/3,cy-ry/2,8,0xFF7897B2);
            for(int i=0;i<8;i++) cube(g,cx-90+(i*47),cy+82+(i%3)*9,4,0xFF4D819B);
        } else if(layer==1) {
            star(g,cx,cy,13);
            bodyLabel(g,f,cx+20,cy-4,"STAR",0xFFFFD58A);
            for(int i=0;i<7;i++) {
                double a=Math.PI*2*i/7.0;
                int x=cx+(int)(Math.cos(a)*rx*.68), y=cy+(int)(Math.sin(a)*ry*.68);
                cube(g,x-4,y-4,8,0xFF6BBFD5);
            }
        } else if(layer==2) {
            star(g,cx,cy,10);
            for(int i=0;i<5;i++) {
                double a=Math.PI*2*i/5.0;
                int x=cx+(int)(Math.cos(a)*rx*.56), y=cy+(int)(Math.sin(a)*ry*.56);
                star(g,x,y,5);
                cube(g,x+14,y-3,5,0xFF69CDE2);
            }
        } else {
            star(g,cx,cy,15);
            bodyLabel(g,f,cx+22,cy-4,"STAR",0xFFFFD58A);
            int[] sizes={9,7,6,8,5,7};
            for(int i=0;i<sizes.length;i++) {
                double a=Math.PI*2*i/sizes.length;
                int x=cx+(int)(Math.cos(a)*(42+i*20));
                int y=cy+(int)(Math.sin(a)*(26+i*12));
                ellipse(g,cx,cy,42+i*20,26+i*12,0x663D91A8);
                cube(g,x-sizes[i]/2,y-sizes[i]/2,sizes[i],0xFF6BBFD5);
                if(i%2==0) cube(g,x+9,y-8,3,0xFF9AB9C8);
            }
        }
        g.drawString(f,"□  PLANET",58,h-48,0xFF8CCFE0,false);
        g.drawString(f,"·  MOON",148,h-48,0xFF9AB9C8,false);
        g.drawString(f,"✦  STAR",222,h-48,0xFFFFD58A,false);
        g.drawString(f,"Preview geometry only — no world catalog or simulation data loaded",58,h-31,MUTED,false);
    }

    private static void drawPolitics(GuiGraphics g, Font f, int w, int h, int mx, int my) {
        int cy=h/2, cx=w/2;
        for(int i=0;i<7;i++) {
            double a=Math.PI*2*i/7.0;
            int x=cx+(int)(Math.cos(a)*Math.min(230,w*.28));
            int y=cy+(int)(Math.sin(a)*Math.min(130,h*.25));
            line(g,cx,cy,x,y,0x665B9AB0);
            node(g,x,y,0xFF6D91AA);
            if(mx>=x-9&&mx<=x+9&&my>=y-9&&my<=y+9)
                g.renderTooltip(f,net.minecraft.network.chat.Component.literal("Realm / polity slot — no data"),mx,my);
        }
        node(g,cx,cy,CYAN);
        g.drawString(f,"POLITICAL WORKSPACE",58,52,TEXT,false);
        g.drawString(f,"Dynasties · realms · vassalage · diplomacy · laws",58,68,MUTED,false);
        g.drawCenteredString(f,"NO WORLD OR POLITICAL DATA CONNECTED",cx,h-48,MUTED);
    }

    private static void drawSettlements(GuiGraphics g, Font f, int w, int h, int mx, int my) {
        int cx=w/2, cy=h/2;
        int cell=34, cols=Math.max(5,Math.min(11,(w-180)/cell)), rows=Math.max(3,Math.min(7,(h-150)/cell));
        int left=cx-cols*cell/2, top=cy-rows*cell/2;
        for(int r=0;r<rows;r++) for(int c=0;c<cols;c++) {
            int x=left+c*cell,y=top+r*cell;
            g.fill(x+2,y+2,x+cell-2,y+cell-2,0x221B3C4C);
            g.fill(x+2,y+2,x+cell-2,y+3,0x553E7185);
            if(mx>=x&&mx<x+cell&&my>=y&&my<y+cell)
                g.fill(x+2,y+2,x+cell-2,y+cell-2,0x5541A8BF);
        }
        g.drawString(f,"SETTLEMENT WORKSPACE",58,52,TEXT,false);
        g.drawString(f,"Districts · plots · roads · households · production",58,68,MUTED,false);
        g.drawCenteredString(f,"EMPTY PLANNING GRID — NO SETTLEMENT DATA",cx,h-48,MUTED);
    }

    private static void drawRts(GuiGraphics g, Font f, int w, int h, int mx, int my) {
        int left=56,top=58,right=w-24,bottom=h-30;
        for(int x=left;x<right;x+=32) g.fill(x,top,x+1,bottom,0x183A6375);
        for(int y=top;y<bottom;y+=32) g.fill(left,y,right,y+1,0x183A6375);
        int cx=w/2,cy=h/2;
        g.fill(cx-2,top,cx+2,bottom,0x223A9FB5);
        g.fill(left,cy-1,right,cy+1,0x223A9FB5);
        g.drawString(f,"TACTICAL WORKSPACE",58,40,TEXT,false);
        g.drawString(f,"Warcraft III-inspired command layout · no units or battle state",58,54,MUTED,false);
        g.drawCenteredString(f,"NO ARMY DATA CONNECTED",cx,bottom-12,MUTED);
    }

    private static void ellipse(GuiGraphics g,int cx,int cy,double rx,double ry,int color) {
        int steps=160,px=0,py=0;
        for(int i=0;i<=steps;i++) {
            double a=Math.PI*2*i/steps;
            int x=cx+(int)Math.round(Math.cos(a)*rx),y=cy+(int)Math.round(Math.sin(a)*ry);
            if(i>0) line(g,px,py,x,y,color);
            px=x;py=y;
        }
    }
    private static void line(GuiGraphics g,int x0,int y0,int x1,int y1,int color) {
        int dx=Math.abs(x1-x0),sx=x0<x1?1:-1,dy=-Math.abs(y1-y0),sy=y0<y1?1:-1,err=dx+dy;
        while(true){g.fill(x0,y0,x0+1,y0+1,color);if(x0==x1&&y0==y1)break;int e=2*err;if(e>=dy){err+=dy;x0+=sx;}if(e<=dx){err+=dx;y0+=sy;}}
    }
    private static void cube(GuiGraphics g,int x,int y,int size,int color) {
        g.fill(x-2,y-2,x+size+2,y+size+2,0x334CBBD0);
        g.fill(x,y,x+size,y+size,color);
        g.fill(x,y,x+size,y+1,0xFFD9F5FF);
        g.fill(x,y,x+1,y+size,0x887ED9E9);
        g.fill(x+size-1,y,x+size,y+size,0x55304455);
    }
    private static void star(GuiGraphics g,int x,int y,int r) {
        g.fill(x-r-5,y-r-5,x+r+5,y+r+5,0x163C9AB2);
        g.fill(x-r,y-r,x+r,y+r,0x44F5C978);
        g.fill(x-r/2,y-r/2,x+r/2,y+r/2,0xFFFFE2A0);
        g.fill(x-1,y-r-3,x+2,y+r+3,0xAAFFF0C8);
        g.fill(x-r-3,y-1,x+r+3,y+2,0xAAFFF0C8);
    }
    private static void node(GuiGraphics g,int x,int y,int color) {
        g.fill(x-7,y-7,x+8,y+8,0x334CBBD0);
        g.fill(x-4,y-4,x+5,y+5,color);
        g.fill(x-4,y-4,x+5,y-3,0xFFD9F5FF);
    }
    private static void bodyLabel(GuiGraphics g,Font f,int x,int y,String text,int color) {
        g.drawString(f,text,x,y,color,false);
    }
}
