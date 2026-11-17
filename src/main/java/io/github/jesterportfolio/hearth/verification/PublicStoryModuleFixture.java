package io.github.jesterportfolio.hearth.verification;

import io.github.jesterportfolio.hearth.story.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** 기존 공개 합성 자료를 새 격리 검사의 정상 콘텐츠 형식으로 묶습니다. 운영 자산 설치기가 아닙니다. */
final class PublicStoryModuleFixture {
    private PublicStoryModuleFixture(){}
    private static Map<String,Object> read(JavaPlugin plugin,String file)throws Exception{var yaml=new YamlConfiguration();try(var input=Objects.requireNonNull(plugin.getResource(file))){yaml.loadFromString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}return StoryRuntime.map(yaml);}
    private static List<Object> list(Map<String,Object> root,String key){return new ArrayList<>((List<?>)Objects.requireNonNull(root.get(key)));}
    @SuppressWarnings("unchecked") static void enrich(JavaPlugin plugin,YamlConfiguration yaml)throws Exception{
        var root=StoryRuntime.map(yaml);var quests=StoryContentBundle.section(root,"quests");var exploration=StoryContentBundle.section(root,"exploration");var rules=StoryContentBundle.section(root,"rules");
        var craftQuests=read(plugin,"crafting-quest-demo.yml");var course=read(plugin,"gathering-workshop-course.yml");var escort=read(plugin,"escort-demo.yml");var plan=read(plugin,"public-course-demo.yml");var travel=read(plugin,"fast-travel-demo.yml");
        var allQuests=list(quests,"quests");for(var source:List.of(read(plugin,"dungeon-quest-demo.yml"),craftQuests,course,escort))allQuests.addAll(list(source,"quests"));quests.put("quests",allQuests);
        var actors=list(quests,"npcs");actors.addAll(list(plan,"npcs"));quests.put("npcs",actors);
        for(String key:List.of("places","journal")){var rows=list(exploration,key);rows.addAll(list(craftQuests,key));rows.addAll(list(course,key));if(key.equals("places"))rows.addAll(list(travel,"extra-places"));exploration.put(key,rows);}
        var ruleRows=list(rules,"quests");ruleRows.addAll(list(StoryContentBundle.section(escort,"quest-rules"),"quests"));rules.put("quests",ruleRows);
        var craft=read(plugin,"crafting-demo.yml");var recipes=list(craft,"recipes");recipes.addAll(list(course,"recipes"));craft.put("recipes",recipes);var facilities=list(craft,"facilities");
        for(Object value:facilities){var facility=(Map<String,Object>)value;if(facility.get("id").equals(course.get("facility-id"))){var ids=list(facility,"recipes");for(Object recipe:list(course,"recipes"))ids.add(((Map<?,?>)recipe).get("id"));facility.put("recipes",ids);}}
        craft.put("facilities",facilities);var modules=new LinkedHashMap<String,Object>();modules.put("crafting",craft);var gathering=read(plugin,"gathering-workshop-demo.yml");gathering.put("interval-ms",100);gathering.put("warning-ms",0);modules.put("gathering",gathering);modules.put("gathering-facilities",read(plugin,"gathering-facility-demo.yml"));modules.put("travel",travel);modules.put("escort",escort);modules.put("combat",read(plugin,"combat-s4-demo.yml"));modules.put("field",read(plugin,"field-demo.yml"));modules.put("dungeon",read(plugin,"dungeon-play.yml"));modules.put("playthrough",plan);modules.put("preparation",read(plugin,"play-preparation-demo.yml"));modules.put("course-route",plan);modules.put("gathering-quests",list(course,"quests").stream().map(q->((Map<?,?>)q).get("id")).toList());root.put("modules",modules);
        for(var item:root.entrySet())yaml.set(item.getKey(),item.getValue());
    }
    static void prepare(World world,StoryContentBundle bundle){
        var modules=bundle.modules();if(modules.gathering()!=null)for(var cell:modules.gathering().area().cells())if(!world.getBlockAt(cell.x(),cell.y(),cell.z()).getType().isAir())throw new IllegalStateException("PUBLIC_FIXTURE_GATHERING_ORIGINAL_BLOCK_CONFLICT");if(modules.crafting()!=null)for(var f:modules.crafting().facilities().values())place(world,f.x(),f.y(),f.z(),Material.CRAFTING_TABLE);
        if(modules.travel()!=null)for(var s:modules.travel().stations().values())place(world,(int)s.facility().x(),(int)s.facility().y(),(int)s.facility().z(),Material.valueOf(s.material()));
        if(modules.facilities()!=null)for(var site:modules.facilities().sites())place(world,site.x(),site.y(),site.z(),Material.valueOf(site.material()));
    }
    private static void place(World world,int x,int y,int z,Material material){var block=world.getBlockAt(x,y,z);if(!block.getType().isAir()&&block.getType()!=material)throw new IllegalStateException("PUBLIC_FIXTURE_ORIGINAL_BLOCK_CONFLICT");if(block.getType().isAir())block.setType(material,false);}
}
