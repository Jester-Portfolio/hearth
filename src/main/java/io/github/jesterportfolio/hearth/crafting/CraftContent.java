package io.github.jesterportfolio.hearth.crafting;

import java.util.*;

/** 외부 데이터는 순정 레시피와 시설만 참조합니다. 운영 제작 밸런스를 추가하지 않습니다. */
public record CraftContent(Map<String,Recipe> recipes,Map<String,Facility> facilities) {
    public record Recipe(String id,String title,String key,List<String> matrix) {
        public Recipe { identifier(id);if(title==null||title.isBlank()||title.length()>80||key==null||!key.matches("minecraft:[a-z0-9_/]+"))throw new IllegalArgumentException("CRAFT_RECIPE");matrix=List.copyOf(matrix);if(matrix.size()!=9||matrix.stream().anyMatch(s->!s.matches("[A-Z_]+"))||matrix.stream().allMatch("AIR"::equals))throw new IllegalArgumentException("CRAFT_MATRIX"); }
    }
    public record Facility(String id,int x,int y,int z,List<String> recipes) {
        public Facility {identifier(id);recipes=List.copyOf(recipes);if(Math.abs((long)x)>29999984||Math.abs((long)z)>29999984||y< -64||y>319||recipes.isEmpty()||recipes.size()>45||new HashSet<>(recipes).size()!=recipes.size())throw new IllegalArgumentException("CRAFT_FACILITY");}
    }
    public CraftContent {recipes=Map.copyOf(recipes);facilities=Map.copyOf(facilities);if(recipes.isEmpty()||recipes.size()>45||facilities.isEmpty()||facilities.size()>32)throw new IllegalArgumentException("CRAFT_CONTENT");var positions=new HashSet<String>();for(var f:facilities.values())if(!recipes.keySet().containsAll(f.recipes())||!positions.add(f.x()+"/"+f.y()+"/"+f.z()))throw new IllegalArgumentException("CRAFT_REFERENCE");}
    public static CraftContent parse(Map<?,?> root) {
        if(!(root.get("format") instanceof Number n)||n.doubleValue()!=1||!Boolean.TRUE.equals(root.get("isolated-prototype")))throw new IllegalArgumentException("CRAFT_FORMAT");
        var recipes=new LinkedHashMap<String,Recipe>();var facilities=new LinkedHashMap<String,Facility>();
        for(Object o:list(root.get("recipes"))){var r=map(o);var recipe=new Recipe(text(r,"id"),text(r,"title"),text(r,"recipe-key"),strings(r.get("matrix")));if(recipes.putIfAbsent(recipe.id(),recipe)!=null)throw new IllegalArgumentException("CRAFT_DUPLICATE_ID");}
        for(Object o:list(root.get("facilities"))){var r=map(o);var f=new Facility(text(r,"id"),integer(r,"x"),integer(r,"y"),integer(r,"z"),strings(r.get("recipes")));if(facilities.putIfAbsent(f.id(),f)!=null)throw new IllegalArgumentException("CRAFT_DUPLICATE_ID");}
        return new CraftContent(recipes,facilities);
    }
    private static void identifier(String s){if(s==null||!s.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("CRAFT_ID");}
    private static Map<?,?> map(Object o){if(!(o instanceof Map<?,?> m))throw new IllegalArgumentException("CRAFT_MAP");return m;}
    private static List<?> list(Object o){if(!(o instanceof List<?> l))throw new IllegalArgumentException("CRAFT_LIST");return l;}
    private static List<String> strings(Object o){return list(o).stream().map(v->{if(!(v instanceof String s))throw new IllegalArgumentException("CRAFT_TEXT");return s;}).toList();}
    private static String text(Map<?,?> m,String k){if(!(m.get(k) instanceof String s))throw new IllegalArgumentException("CRAFT_TEXT");return s;}
    private static int integer(Map<?,?> m,String k){if(!(m.get(k) instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.intValue())throw new IllegalArgumentException("CRAFT_POSITION");return n.intValue();}
}
