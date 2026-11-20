package dev.worldforge.mod;

/** Seeded, coordinate-stable planetary fields sampled lazily by virtual raster tiles. */
final class ProceduralPlanetTerrain {
    private final int width,height,seaLevel,minY,maxY,wildernessShare;
    private final Noise continents,relief,detail,climate,moisture,channels;
    private final long seed;

    ProceduralPlanetTerrain(PlanetWorldProfile p) {
        width=p.widthPx; height=p.heightPx; seaLevel=p.seaLevel; minY=p.minY;
        maxY=p.maxY; wildernessShare=p.wildernessSharePct; seed=p.seed;
        continents=new Noise(seed ^ 0x434F4E54494E454EL);
        relief=new Noise(seed ^ 0x52454C494546L);
        detail=new Noise(seed ^ 0x44455441494CL);
        climate=new Noise(seed ^ 0x434C494D415445L);
        moisture=new Noise(seed ^ 0x4D4F4953545552L);
        channels=new Noise(seed ^ 0x524956455253L);
    }

    int heightSample(int x,int z) {
        double continent=continents.fbm2(x*0.00052,z*0.00052,5);
        double mass=continent+relief.noise2(x*0.0011,z*0.0011)*0.13;
        double elevation;
        if(mass < -0.08) {
            double depth=Math.min(1.0,(-0.08-mass)*1.8);
            elevation=seaLevel-5.0-depth*76.0;
        } else {
            double land=Math.min(1.0,(mass+0.08)*1.15);
            double ridge=1.0-Math.abs(relief.noise2(x*0.0019,z*0.0019));
            double mountains=Math.pow(Math.max(0.0,ridge-0.53),2.0)*510.0;
            double hills=continents.noise2(x*0.0037,z*0.0037)*15.0;
            double fine=detail.fbm2(x*0.018,z*0.018,3)*4.0;
            double channel=Math.abs(channels.noise2(x*0.0032,z*0.0032));
            double riverCut=channel<0.018?(0.018-channel)*180.0:0.0;
            elevation=seaLevel+5.0+land*82.0+mountains+hills+fine-riverCut;
        }
        double n=(elevation-minY)/(double)(maxY-minY);
        return (int)Math.round(Math.max(0.0,Math.min(1.0,n))*65535.0);
    }

    int biomeSample(int x,int z) {
        double y=minY+heightSample(x,z)/65535.0*(maxY-minY);
        if(y<seaLevel-24)return 0;
        if(y<seaLevel+2)return 1;
        double latitude=Math.abs((z/(double)Math.max(1,height-1))*2.0-1.0);
        double temperature=1.0-latitude*0.82
                +climate.noise2(x*0.0009,z*0.0009)*0.16-Math.max(0,y-seaLevel)*0.0012;
        double wet=moisture.fbm2(x*0.0013,z*0.0013,4);
        if(y>seaLevel+145)return temperature<0.36?10:6;
        if(temperature<0.20)return wet>0.05?7:10;
        if(temperature<0.38)return wet>0.20?7:4;
        if(wet<-0.28&&temperature>0.48)return 3;
        if(wet>0.48&&temperature>0.48)return 8;
        if(wet>0.36)return 4;
        if(temperature>0.72&&wet<0.08)return 9;
        if(wet>0.15&&temperature>0.40)return 5;
        return 2;
    }

    int riverSample(int x,int z) {
        double y=minY+heightSample(x,z)/65535.0*(maxY-minY);
        if(y<=seaLevel+3)return 0;
        return Math.abs(channels.noise2(x*0.0032,z*0.0032))<0.018?255:0;
    }

    int wildernessSample(int x,int z) {
        if(wildernessShare<=0)return 0;
        long v=((long)x*0x9E3779B97F4A7C15L)^((long)z*0xC2B2AE3D27D4EB4FL)^seed;
        v^=v>>>30; v*=0xBF58476D1CE4E5B9L; v^=v>>>27;
        v*=0x94D049BB133111EBL; v^=v>>>31;
        return Long.remainderUnsigned(v,100)<wildernessShare?255:0;
    }

    int[] findSpawn() {
        int cx=width/2,cz=height/2;
        for(int r=0;r<=4096;r+=32) {
            for(int dz=-r;dz<=r;dz+=32) for(int dx=-r;dx<=r;dx+=32) {
                if(r!=0&&Math.max(Math.abs(dx),Math.abs(dz))!=r)continue;
                int x=Math.max(0,Math.min(width-1,cx+dx));
                int z=Math.max(0,Math.min(height-1,cz+dz));
                double y=minY+heightSample(x,z)/65535.0*(maxY-minY);
                if(y>seaLevel+3)return new int[]{x,z};
            }
        }
        return new int[]{cx,cz};
    }
}
