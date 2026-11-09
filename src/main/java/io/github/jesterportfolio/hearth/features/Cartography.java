package io.github.jesterportfolio.hearth.features;

import java.util.*;

/** 이미 발견하고 접근 가능한 열만 읽습니다. 샘플 수·원형 마스크가 고정된 개인 지도 모델입니다. */
public final class Cartography {
    public record Zone(String id,int minX,int maxX,int minZ,int maxZ){public Zone{if(minX>maxX||minZ>maxZ)throw new IllegalArgumentException("MAP_ZONE");}public boolean contains(int x,int z){return x>=minX&&x<=maxX&&z>=minZ&&z<=maxZ;}}
    public interface Terrain{boolean loaded(int x,int z);byte color(int x,int z);}
    public static byte[] render(int centerX,int centerZ,int scale,boolean round,List<Zone> discovered,Terrain terrain){if(scale<1||scale>16||discovered.size()>256)throw new IllegalArgumentException("MAP_LAYOUT");byte[] pixels=new byte[128*128];for(int y=0;y<32;y++)for(int x=0;x<32;x++){
        int dx=x*4+2-64,dz=y*4+2-64;if(round&&dx*dx+dz*dz>60*60)continue;int wx=Math.addExact(centerX,dx*scale),wz=Math.addExact(centerZ,dz*scale);if(discovered.stream().noneMatch(zone->zone.contains(wx,wz))||!terrain.loaded(wx,wz))continue;byte color=terrain.color(wx,wz);
        for(int iy=y*4;iy<y*4+4;iy++)for(int ix=x*4;ix<x*4+4;ix++)if(!round||(ix-64)*(ix-64)+(iy-64)*(iy-64)<=60*60)pixels[iy*128+ix]=color;
    }return pixels;}
}
