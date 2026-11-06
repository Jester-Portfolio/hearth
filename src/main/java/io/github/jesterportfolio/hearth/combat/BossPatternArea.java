package io.github.jesterportfolio.hearth.combat;

import java.util.*;
import static io.github.jesterportfolio.hearth.combat.CombatContent.*;

/** 공격 판정과 참가자 전용 예고에 같은 불변 좌표를 사용합니다. Bukkit 객체는 보관하지 않습니다. */
public final class BossPatternArea {
    private BossPatternArea(){}
    public static final class Anchor {
        private Pose warning;
        public void begin(Pose pose){warning=Objects.requireNonNull(pose);}
        public void clear(){warning=null;}
        public Pose origin(Pattern pattern,Pose live){
            if(pattern.origin()==Origin.LIVE)return live;
            if(warning==null||!warning.world().equals(live.world()))throw new IllegalStateException("BOSS_AREA_ANCHOR_MISSING_OR_STALE");return warning;
        }
    }
    public static double extent(Pattern p){return switch(p.shape()){case SPHERE->p.range();case CONE->Math.max(p.range(),p.height()/2);case LINE->Math.max(Math.hypot(p.range(),p.width()/2),p.height()/2);};}
    public static boolean contains(Pattern p,Pose origin,Pose target){
        if(!origin.world().equals(target.world()))return false;
        double x=target.x()-origin.x(),y=target.y()-origin.y(),z=target.z()-origin.z(),epsilon=1e-8;
        if(p.shape()==Shape.SPHERE)return x*x+y*y+z*z<=p.range()*p.range()+epsilon;
        if(Math.abs(y)>p.height()/2+epsilon)return false;
        double yaw=Math.toRadians(origin.yaw()),forward=-Math.sin(yaw)*x+Math.cos(yaw)*z,side=Math.cos(yaw)*x+Math.sin(yaw)*z;
        return switch(p.shape()){
            case CONE->x*x+z*z<=p.range()*p.range()+epsilon&&forward+epsilon>=Math.hypot(x,z)*Math.cos(Math.toRadians(p.angle()/2));
            case LINE->forward>=-epsilon&&forward<=p.range()+epsilon&&Math.abs(side)<=p.width()/2+epsilon;
            case SPHERE->throw new AssertionError();
        };
    }
    private static Pose point(Pose origin,double forward,double side){double yaw=Math.toRadians(origin.yaw());return new Pose(origin.world(),origin.x()-Math.sin(yaw)*forward+Math.cos(yaw)*side,origin.y(),origin.z()+Math.cos(yaw)*forward+Math.sin(yaw)*side,origin.yaw(),0);}
    public static List<Pose> boundary(Pattern p,Pose origin){
        var points=new ArrayList<Pose>();
        switch(p.shape()){
            case SPHERE->{for(int i=0;i<24;i++){double angle=Math.PI*2*i/24;points.add(point(origin,p.range()*Math.cos(angle),p.range()*Math.sin(angle)));}}
            case CONE->{points.add(origin);for(int i=0;i<=12;i++){double angle=Math.toRadians(-p.angle()/2+p.angle()*i/12);points.add(point(origin,p.range()*Math.cos(angle),p.range()*Math.sin(angle)));}for(int i=1;i<=2;i++)for(int sign:new int[]{-1,1}){double angle=Math.toRadians(p.angle()/2);points.add(point(origin,p.range()*i/3*Math.cos(angle),sign*p.range()*i/3*Math.sin(angle)));}}
            case LINE->{for(int i=0;i<=5;i++)for(int sign:new int[]{-1,1})points.add(point(origin,p.range()*i/5,sign*p.width()/2));}
        }
        return List.copyOf(points);
    }
}
