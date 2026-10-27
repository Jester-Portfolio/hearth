package io.github.jesterportfolio.hearth.story;

import io.github.jesterportfolio.hearth.quest.*;
import java.util.*;

/** 공유 상태가 불명/실패이면 제한 지역만 닫습니다. 건축 권한·월드 배치 정본은 변경하지 않습니다. */
public final class StoryAccess {
    private final SharedStory story;public StoryAccess(SharedStory story){this.story=story;}
    private boolean unlocked(UUID run,StoryContent.RegionRule rule){var saved=story.cached(run);return rule.policy()==StoryContent.Policy.OPEN||story.healthy(run)&&saved!=null&&(Objects.equals(saved.story().stage(),rule.reached())||saved.completed().contains(rule.reached()));}
    public boolean entry(UUID run,double x,double y,double z){return story.content().regions().stream().filter(r->r.policy()==StoryContent.Policy.ENTRY&&r.bounds().contains(x,y,z)).allMatch(r->unlocked(run,r));}
    public boolean content(UUID run,double x,double y,double z){return story.content().regions().stream().filter(r->r.policy()!=StoryContent.Policy.OPEN&&r.bounds().contains(x,y,z)).allMatch(r->unlocked(run,r));}
    public boolean region(UUID run,String region){return story.content().regions().stream().filter(r->r.region().equals(region)).allMatch(r->unlocked(run,r));}
    public String status(UUID run,String region){return story.content().regions().stream().filter(r->r.region().equals(region)&&!unlocked(run,r)).map(r->r.policy()==StoryContent.Policy.ENTRY?"진입 제한":"콘텐츠 제한").findFirst().orElse("일반 개방");}
    public boolean area(UUID run,QuestContent.Area b){return story.content().regions().stream().filter(r->r.policy()!=StoryContent.Policy.OPEN&&overlap(b,r.bounds())).allMatch(r->unlocked(run,r));}
    public boolean quest(UUID run,QuestContent c){return content(run,c.x(),c.y(),c.z())&&c.goals().stream().allMatch(g->area(run,g.area()));}
    private static boolean overlap(QuestContent.Area a,QuestContent.Area b){return a.minX()<=b.maxX()&&b.minX()<=a.maxX()&&a.minY()<=b.maxY()&&b.minY()<=a.maxY()&&a.minZ()<=b.maxZ()&&b.minZ()<=a.maxZ();}
}
