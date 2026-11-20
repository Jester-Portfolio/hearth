package io.github.jesterportfolio.hearth.survival.skills;

import java.util.*;

/** 월드 이름 대신 소유 레지스트리의 역할로 판단하는 불변 접근 정책. */
public record WorldAccessPolicy(Map<Role,List<Rule>> rules) {
    public enum Role { PUBLIC_HOME, PRIVATE_HOME, NOVICE_RESOURCE, RESOURCE, NETHER, END;
        public boolean residence(){return this==PUBLIC_HOME||this==PRIVATE_HOME;}
    }
    public enum Kind { SPECIFIC, AVERAGE, SUM, ALL }
    public record Levels(boolean loaded, Map<String,Integer> values) {
        public Levels { values=Map.copyOf(values); }
        public static Levels unavailable(){return new Levels(false,Map.of());}
    }
    public record Rule(Kind kind,List<String> skills,int minimum) {
        public Rule {
            Objects.requireNonNull(kind);skills=List.copyOf(skills);
            if(minimum<0||skills.isEmpty()||skills.size()>128||new HashSet<>(skills).size()!=skills.size()||kind==Kind.SPECIFIC&&skills.size()!=1||skills.stream().anyMatch(s->!s.matches("[a-z0-9_]+/[a-z0-9_]+")))throw new IllegalArgumentException("SKILLS_ACCESS_RULE");
        }
        public boolean allows(Levels levels){
            if(!levels.loaded()||skills.stream().anyMatch(s->!levels.values().containsKey(s)||levels.values().get(s)<0))return false;
            long sum=skills.stream().mapToLong(levels.values()::get).sum();
            return switch(kind){case SPECIFIC->levels.values().get(skills.getFirst())>=minimum;case AVERAGE->sum>=(long)minimum*skills.size();case SUM->sum>=minimum;case ALL->skills.stream().allMatch(s->levels.values().get(s)>=minimum);};
        }
        public String description(){return switch(kind){case SPECIFIC->skills.getFirst();case AVERAGE->"평균 "+String.join(", ",skills);case SUM->"합산 "+String.join(", ",skills);case ALL->"각 숙련도 "+String.join(", ",skills);}+" ≥ "+minimum;}
    }
    public record Decision(boolean allowed,String message){}
    public WorldAccessPolicy {
        var copy=new EnumMap<Role,List<Rule>>(Role.class);
        for(var role:Role.values()){if(!rules.containsKey(role))throw new IllegalArgumentException("SKILLS_ACCESS_ROLE_MISSING");var list=List.copyOf(rules.get(role));if(role.residence()&&!list.isEmpty())throw new IllegalArgumentException("RESIDENCE_HAS_NO_LEVEL_GATE");copy.put(role,list);}
        rules=Map.copyOf(copy);
    }
    public static WorldAccessPolicy unrestricted(){var map=new EnumMap<Role,List<Rule>>(Role.class);for(var role:Role.values())map.put(role,List.of());return new WorldAccessPolicy(map);}
    public Decision evaluate(Role role,boolean housingAllowed,Levels levels){
        if(role==null)return new Decision(false,"등록되지 않은 월드입니다.");
        if(!housingAllowed)return new Decision(false,"거주지 접근 권한을 확인해 주세요.");
        if(role.residence())return new Decision(true,"");
        if(!levels.loaded())return new Decision(false,"숙련도 정보를 불러오는 중입니다. 잠시 후 다시 이용하세요.");
        for(var rule:rules.get(role))if(!rule.allows(levels))return new Decision(false,"입장 조건: "+rule.description());
        return new Decision(true,"");
    }
    public Set<String> requiredSkills(){var result=new TreeSet<String>();rules.values().forEach(list->list.forEach(r->result.addAll(r.skills())));return Set.copyOf(result);}
    public static WorldAccessPolicy parse(Properties p){
        if(!"1".equals(p.getProperty("format")))throw new IllegalArgumentException("SKILLS_ACCESS_FORMAT");
        var used=new HashSet<String>(Set.of("format"));var rules=new EnumMap<Role,List<Rule>>(Role.class);
        for(var role:Role.values()){
            String key=role.name().toLowerCase(Locale.ROOT)+".rules";used.add(key);
            String value=Objects.requireNonNull(p.getProperty(key),"SKILLS_ACCESS_ROLE_MISSING");var list=new ArrayList<Rule>();
            if(!value.equals("none"))for(String name:value.split(",",-1)){
                name=name.trim();if(!name.matches("[a-z0-9_]{1,40}"))throw new IllegalArgumentException("SKILLS_ACCESS_RULE_ID");
                String prefix="rule."+name+".";for(String field:List.of("kind","skills","minimum"))used.add(prefix+field);
                var kind=Kind.valueOf(p.getProperty(prefix+"kind","").toUpperCase(Locale.ROOT));
                String raw=Objects.requireNonNull(p.getProperty(prefix+"skills"));
                list.add(new Rule(kind,Arrays.stream(raw.split(",",-1)).map(String::trim).toList(),Integer.parseInt(p.getProperty(prefix+"minimum"))));
            }
            if(list.size()>32)throw new IllegalArgumentException("SKILLS_ACCESS_RULE_LIMIT");rules.put(role,list);
        }
        if(!used.equals(p.stringPropertyNames()))throw new IllegalArgumentException("SKILLS_ACCESS_UNKNOWN_KEY");
        return new WorldAccessPolicy(rules);
    }
}
