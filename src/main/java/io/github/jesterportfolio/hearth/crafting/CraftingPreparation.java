package io.github.jesterportfolio.hearth.crafting;

import java.util.*;

/** 현장에 등록된 제작법과 본인 일반 재료로 만드는 읽기 전용 준비 순서입니다. */
public final class CraftingPreparation {
    private static final int MAX_STEPS=32, MAX_VISITS=128, MAX_ITEMS=1_000_000;
    public enum Status { READY, NEEDS_MATERIALS, UNAVAILABLE }
    public enum Reason { NONE, CYCLE, LIMIT }
    public record Formula(String id,String title,String output,int amount,Map<String,Integer> ingredients,Map<String,Integer> remainders) {
        public Formula {
            if(id==null||!id.matches("[a-z][a-z0-9_]{0,63}")||title==null||title.isBlank()||title.length()>80||!material(output)||amount<1||amount>64)throw new IllegalArgumentException("CRAFT_PREPARATION_FORMULA");
            ingredients=counts(ingredients);remainders=counts(remainders);
            if(ingredients.isEmpty()||ingredients.values().stream().mapToLong(Integer::longValue).sum()>9||remainders.values().stream().mapToLong(Integer::longValue).sum()>576)throw new IllegalArgumentException("CRAFT_PREPARATION_FORMULA");
        }
    }
    public record Step(String recipe,String title,int times,String output,int produced) {}
    public record Plan(List<Step> goals,Status status,Reason reason,List<Step> steps,Map<String,Integer> additional,Map<String,Integer> ownedUsed,Map<String,Integer> surplus) {
        public Plan {goals=List.copyOf(goals);steps=List.copyOf(steps);additional=counts(additional);ownedUsed=counts(ownedUsed);surplus=counts(surplus);}
        public List<String> lines(Map<String,String> names){
            var lines=new ArrayList<String>();
            if(status==Status.UNAVAILABLE)return List.of("등록된 제작법으로 준비 순서를 계산하지 못했습니다. 각 제작법의 재료를 확인하세요.");
            lines.add(status==Status.READY?"보유 재료로 준비할 수 있습니다.":"추가 원재료를 준비해야 합니다.");
            for(var goal:goals)lines.add("목표: "+goal.title()+" "+goal.times()+"회 / 결과 "+goal.produced()+"개");
            lines.add("제작 순서");
            int index=1;for(var step:steps)lines.add(index+++". "+step.title()+" "+step.times()+"회 → "+name(names,step.output())+" "+step.produced()+"개");
            summary(lines,"추가 원재료",additional,names);summary(lines,"사용할 보유 재료",ownedUsed,names);summary(lines,"제작 후 남는 재료",surplus,names);
            lines.add("등록 순서에 따른 준비 예시입니다. 최저 비용이나 보관 공간을 보장하지 않습니다.");
            lines.add("횟수가 선택 버튼에 없으면 1회씩 나누어 제작하세요.");
            return List.copyOf(lines);
        }
    }
    private CraftingPreparation() {}
    private static String name(Map<String,String> names,String material){return names.getOrDefault(material,material);}
    private static void summary(List<String> lines,String title,Map<String,Integer> rows,Map<String,String> names){if(!rows.isEmpty()){lines.add(title);rows.forEach((m,n)->lines.add(name(names,m)+" × "+n));}}
    private static boolean material(String value){return value!=null&&value.matches("[A-Z_]+")&&!value.equals("AIR");}
    private static Map<String,Integer> counts(Map<String,Integer> values){var copy=new LinkedHashMap<String,Integer>();values.forEach((m,n)->{if(!material(m)||n==null||n<1||n>MAX_ITEMS)throw new IllegalArgumentException("CRAFT_PREPARATION_COUNT");copy.put(m,n);});return Collections.unmodifiableMap(copy);}
    public static Plan plan(List<Formula> formulas,String selected,int times,Map<String,Integer> owned){
        return plan(formulas,List.of(new CraftingBatch.Selection(selected,times)),owned);
    }
    /** 여러 목표의 재료를 한 번만 배정합니다. 완료할 목표 결과는 다음 목표의 재료로 소모하지 않습니다. */
    public static Plan plan(List<Formula> formulas,List<CraftingBatch.Selection> selected,Map<String,Integer> owned){
        formulas=List.copyOf(formulas);selected=List.copyOf(selected);
        if(formulas.isEmpty()||formulas.size()>45||selected.isEmpty()||selected.size()>8)throw new IllegalArgumentException("CRAFT_PREPARATION_FORMULAS");
        var byId=new LinkedHashMap<String,Formula>();for(var formula:formulas)if(byId.putIfAbsent(formula.id(),formula)!=null)throw new IllegalArgumentException("CRAFT_PREPARATION_DUPLICATE");
        var goals=new ArrayList<Step>();var ids=new HashSet<String>();
        for(var choice:selected){var goal=byId.get(choice.recipe());if(goal==null||!ids.add(goal.id()))throw new IllegalArgumentException("CRAFT_PREPARATION_RECIPE");goals.add(new Step(goal.id(),goal.title(),choice.times(),goal.output(),goal.amount()*choice.times()));}
        var relevant=new HashSet<String>();for(var formula:formulas){relevant.addAll(formula.ingredients().keySet());relevant.addAll(formula.remainders().keySet());relevant.add(formula.output());}
        var inventory=new LinkedHashMap<String,Integer>();owned.forEach((m,n)->{if(!material(m)||n==null||n<0||n>MAX_ITEMS)throw new IllegalArgumentException("CRAFT_PREPARATION_COUNT");if(n>0&&relevant.contains(m))inventory.put(m,n);});
        var builder=new Builder(formulas,inventory);
        try{
            for(var goal:goals){builder.craft(byId.get(goal.recipe()),goal.times());builder.stock.put(goal.output(),builder.stock.get(goal.output())-goal.produced());}
            var surplus=new LinkedHashMap<String,Integer>();builder.stock.forEach((m,n)->{int remaining=n-builder.ownedRemaining.getOrDefault(m,0);if(remaining>0)surplus.put(m,remaining);});
            return new Plan(goals,builder.additional.isEmpty()?Status.READY:Status.NEEDS_MATERIALS,Reason.NONE,builder.steps,builder.additional,builder.ownedUsed,surplus);
        }catch(Blocked error){return new Plan(goals,Status.UNAVAILABLE,error.reason,List.of(),Map.of(),Map.of(),Map.of());}
        catch(ArithmeticException error){return new Plan(goals,Status.UNAVAILABLE,Reason.LIMIT,List.of(),Map.of(),Map.of(),Map.of());}
    }
    private static final class Blocked extends RuntimeException {final Reason reason;Blocked(Reason reason){this.reason=reason;}}
    private static final class Builder {
        final List<Formula> formulas;final Map<String,Integer> stock,ownedRemaining,ownedUsed=new LinkedHashMap<>(),additional=new LinkedHashMap<>();
        final List<Step> steps=new ArrayList<>();final Set<String> active=new HashSet<>();int visits;
        Builder(List<Formula> formulas,Map<String,Integer> owned){this.formulas=formulas;stock=new LinkedHashMap<>(owned);ownedRemaining=new HashMap<>(owned);}
        void add(Map<String,Integer> to,String material,int amount){int value=Math.addExact(to.getOrDefault(material,0),amount);if(value>MAX_ITEMS)throw new Blocked(Reason.LIMIT);to.put(material,value);}
        void take(String material,int amount){
            int available=Math.min(amount,stock.getOrDefault(material,0));stock.put(material,stock.getOrDefault(material,0)-available);
            int fromOwned=Math.min(available,ownedRemaining.getOrDefault(material,0));if(fromOwned>0){ownedRemaining.put(material,ownedRemaining.get(material)-fromOwned);add(ownedUsed,material,fromOwned);}
            int missing=amount-available;if(missing==0)return;
            var recipe=formulas.stream().filter(f->f.output().equals(material)).findFirst();
            if(recipe.isEmpty()){add(additional,material,missing);return;}
            craft(recipe.get(),(missing+recipe.get().amount()-1)/recipe.get().amount());
            if(stock.getOrDefault(material,0)<missing)throw new Blocked(Reason.CYCLE);
            stock.put(material,stock.get(material)-missing);
        }
        void craft(Formula formula,int times){
            if(++visits>MAX_VISITS||active.size()>=16||steps.size()>=MAX_STEPS)throw new Blocked(Reason.LIMIT);
            if(!active.add(formula.id()))throw new Blocked(Reason.CYCLE);
            for(var entry:formula.ingredients().entrySet())take(entry.getKey(),Math.multiplyExact(entry.getValue(),times));
            active.remove(formula.id());int produced=Math.multiplyExact(formula.amount(),times);add(stock,formula.output(),produced);
            formula.remainders().forEach((m,n)->add(stock,m,Math.multiplyExact(n,times)));
            if(steps.size()>=MAX_STEPS)throw new Blocked(Reason.LIMIT);
            steps.add(new Step(formula.id(),formula.title(),times,formula.output(),produced));
        }
    }
}
