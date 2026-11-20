package io.github.jesterportfolio.hearth.survival.enchants;

import java.util.*;
import java.util.function.ToDoubleFunction;

/** 한 번 파싱하는 제한된 수식. 스크립트/클래스/명령 실행은 허용하지 않는다. */
public final class Formula {
    private interface Node { double value(Map<String,Double> variables); }
    private final String source; private final Node root;
    private Formula(String source,Node root){this.source=source;this.root=root;}
    public static Formula of(String text){if(text==null||text.isBlank()||text.length()>512)throw new IllegalArgumentException("ENCHANT_FORMULA_SIZE");var p=new Parser(text);Node root=p.expression(0);p.space();if(p.at!=text.length())throw p.bad();return new Formula(text,root);}
    public double evaluate(Map<String,Double> variables){double result=root.value(variables);if(!Double.isFinite(result)||Math.abs(result)>1e12)throw new IllegalArgumentException("ENCHANT_FORMULA_VALUE");return result;}
    public String toString(){return source;}
    private static final class Parser {
        final String s;int at,nodes;
        Parser(String s){this.s=s;}
        IllegalArgumentException bad(){return new IllegalArgumentException("ENCHANT_FORMULA_AT_"+at);}
        void space(){while(at<s.length()&&Character.isWhitespace(s.charAt(at)))at++;}
        boolean take(String x){space();if(s.startsWith(x,at)){at+=x.length();return true;}return false;}
        Node node(Node n){if(++nodes>128)throw bad();return n;}
        Node expression(int depth){if(depth>24)throw bad();Node a=conjunction(depth+1);while(take("||")){Node b=conjunction(depth+1),left=a;a=node(v->left.value(v)!=0||b.value(v)!=0?1:0);}return a;}
        Node conjunction(int depth){Node a=compare(depth);while(take("&&")){Node b=compare(depth),left=a;a=node(v->left.value(v)!=0&&b.value(v)!=0?1:0);}return a;}
        Node compare(int d){Node a=sum(d);for(String op:List.of("<=",">=","==","!=","<",">"))if(take(op)){Node b=sum(d),left=a;return node(v->{double x=left.value(v),y=b.value(v);return switch(op){case "<="->x<=y?1:0;case ">="->x>=y?1:0;case "=="->x==y?1:0;case "!="->x!=y?1:0;case "<"->x<y?1:0;default->x>y?1:0;};});}return a;}
        Node sum(int d){Node a=product(d);while(true){if(take("+")){Node b=product(d),left=a;a=node(v->left.value(v)+b.value(v));}else if(take("-")){Node b=product(d),left=a;a=node(v->left.value(v)-b.value(v));}else return a;}}
        Node product(int d){Node a=atom(d);while(true){if(take("*")){Node b=atom(d),left=a;a=node(v->left.value(v)*b.value(v));}else if(take("/")){Node b=atom(d),left=a;a=node(v->{double den=b.value(v);if(den==0)throw bad();return left.value(v)/den;});}else return a;}}
        Node atom(int d){if(d>24)throw bad();if(take("-")){Node a=atom(d+1);return node(v->-a.value(v));}if(take("!")){Node a=atom(d+1);return node(v->a.value(v)==0?1:0);}if(take("(")){Node a=expression(d+1);if(!take(")"))throw bad();return a;}space();int start=at;if(at<s.length()&&(Character.isDigit(s.charAt(at))||s.charAt(at)=='.')){while(at<s.length()&&(Character.isDigit(s.charAt(at))||s.charAt(at)=='.'))at++;double x=Double.parseDouble(s.substring(start,at));if(!Double.isFinite(x))throw bad();return node(v->x);}while(at<s.length()&&(Character.isLetterOrDigit(s.charAt(at))||s.charAt(at)=='_'))at++;if(start==at)throw bad();String name=s.substring(start,at);if(take("(")){Node a=expression(d+1);if(Set.of("abs","floor","ceil").contains(name)){if(!take(")"))throw bad();return node(v->switch(name){case "abs"->Math.abs(a.value(v));case "floor"->Math.floor(a.value(v));default->Math.ceil(a.value(v));});}if(!Set.of("min","max","clamp").contains(name)||!take(","))throw bad();Node b=expression(d+1);if(name.equals("clamp")){if(!take(","))throw bad();Node c=expression(d+1);if(!take(")"))throw bad();return node(v->Math.clamp(a.value(v),b.value(v),c.value(v)));}if(!take(")"))throw bad();return node(v->name.equals("min")?Math.min(a.value(v),b.value(v)):Math.max(a.value(v),b.value(v)));}if(!Set.of("level","damage","health","max_health","target_health","allies","sneaking","xp","distance").contains(name))throw bad();return node(v->Objects.requireNonNull(v.get(name),name));}
    }
}
