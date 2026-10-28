import java.util.regex.*;
import org.mozilla.javascript.*;
public class RegexProbes {
 public static void main(String[] args) {
  String[][] cases={{"identity-a","\\a","a"},{"unmatched-backref","^(a|(b))\\2$","a"},{"astral-dot-no-u","^.$","\ud83d\ude00"},{"ascii-identity-u-braces","\\u{61}","a"},{"named-underscore","(?<user_id>\\w+)","abc"},{"js-empty-negative-class","[^]","a"},{"java-intersection","[a-z&&[^b]]","c"}};
  for(String[] c:cases)try{Matcher m=Pattern.compile(c[1]).matcher(c[2]);System.out.println("java8 "+c[0]+"="+m.find());}catch(Exception e){System.out.println("java8 "+c[0]+"=ERROR:"+e.getClass().getSimpleName());}
  Context cx=Context.enter();cx.setLanguageVersion(Context.VERSION_ES6);cx.setOptimizationLevel(-1);
  try{Scriptable scope=cx.initStandardObjects();String[] snippets={"new RegExp('a','g').flags","new RegExp('a','y').flags","new RegExp('.','s').flags","new RegExp('.','u').flags","new RegExp('a','d').flags","new RegExp('[a&&a]','v').flags","new RegExp('(?<id>a)').exec('a')[1]","new RegExp('(?<=a)b').exec('ab')[0]"};
   for(String snippet:snippets)try{System.out.println("rhino "+snippet+" => "+Context.toString(cx.evaluateString(scope,snippet,"probe",1,null)));}catch(Exception e){System.out.println("rhino "+snippet+" => ERROR:"+e.getMessage());}
  }finally{Context.exit();}
 }
}
