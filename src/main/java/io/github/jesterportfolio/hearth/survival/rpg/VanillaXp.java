package io.github.jesterportfolio.hearth.survival.rpg;
/** native 전체 포인트와 레벨 진행률을 변환한다. getTotalExperience의 누적 사용값과 혼동하지 않는다. */
public final class VanillaXp {
    private VanillaXp(){}
    public static long at(int level){if(level<0||level>20000)throw new IllegalArgumentException("XP_LEVEL");long l=level;return level<=16?l*l+6*l:level<=31?(5*l*l-81*l+720)/2:(9*l*l-325*l+4440)/2;}
    public static int next(int level){return (int)(at(level+1)-at(level));}
    public record Value(int level,int progress,float fraction){}
    public static Value decode(long points){if(points<0||points>at(19999))throw new IllegalArgumentException("XP_POINTS");int lo=0,hi=19999;while(lo<hi){int mid=(lo+hi+1)/2;if(at(mid)<=points)lo=mid;else hi=mid-1;}int progress=(int)(points-at(lo));return new Value(lo,progress,(float)progress/next(lo));}
}
