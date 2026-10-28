package com.snoworca.naruserve.routing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * Immutable Java regular-expression path. Matching searches the original raw path using a fresh
 * {@link Matcher}; it never applies string-route policies or percent-decodes captures.
 *
 * <p>Named captures retain Java names. Unnamed captures receive their own zero-based ordinal keys,
 * so {@code (a)(?<name>b)(c)} has keys {@code 0,name,1}. Java's actual group indices include both kinds.
 * Numeric group access avoids {@code Matcher.group(String)}, which is unavailable on Android API 21.
 */
// @req FR-ROUTE-019
public final class RegexPathPattern {
    private static final int LEXICAL_FLAGS = Pattern.COMMENTS | Pattern.UNIX_LINES;
    private final Pattern pattern;
    private final List<Capture> groups;
    private final List<String> keys;

    private RegexPathPattern(Pattern pattern,List<Capture> groups) {
        this.pattern=pattern;
        this.groups=Collections.unmodifiableList(new ArrayList<>(groups));
        List<String> names=new ArrayList<>();for(Capture group:groups)names.add(group.key);
        this.keys=Collections.unmodifiableList(names);
    }

    /**
     * Compiles metadata for an existing Pattern without changing its matching behavior.
     *
     * <p>Embedded flags can erase the original COMMENTS/UNIX_LINES settings from Pattern's public
     * metadata. Feasible initial lexical modes are checked against the actual group count. If modes
     * imply different capture keys, this method rejects registration rather than inventing names;
     * use {@link #compile(String, int)} with the original source and flags for that case.
     */
    // @req FR-ROUTE-019 AC-2 AC-3 AC-4 AC-6
    public static RegexPathPattern compile(Pattern pattern) {
        Objects.requireNonNull(pattern,"pattern");
        validateFlags(pattern.flags());
        if((pattern.flags()&Pattern.LITERAL)!=0) return new RegexPathPattern(pattern,Collections.emptyList());
        int actualCount=pattern.matcher("").groupCount();
        List<Capture> selected=null;
        for(int mode:new int[]{0,Pattern.COMMENTS,Pattern.UNIX_LINES,LEXICAL_FLAGS}) {
            int flags=(pattern.flags()&~LEXICAL_FLAGS)|mode;
            try {
                Pattern candidate=Pattern.compile(pattern.pattern(),flags);
                if((candidate.flags()&LEXICAL_FLAGS)!=(pattern.flags()&LEXICAL_FLAGS)
                        || candidate.matcher("").groupCount()!=actualCount) continue;
                List<Capture> groups=scan(pattern.pattern(),flags,actualCount);
                if(selected!=null && !sameGroups(selected,groups)) throw ambiguous();
                selected=groups;
            } catch(PatternSyntaxException invalidMode) {
                // The original Pattern is valid, but this guessed initial lexical mode is not.
            }
        }
        if(selected==null) throw new IllegalArgumentException("Cannot derive Java capture metadata; use compile(String source, int flags) with the original flags");
        return new RegexPathPattern(pattern,selected);
    }

    /** Precise Pattern.compile analogue retaining the initial flags for capture-metadata scanning. */
    // @req FR-ROUTE-019 AC-1 AC-2 AC-3 AC-6
    public static RegexPathPattern compile(String source,int flags) {
        Objects.requireNonNull(source,"source");
        validateFlags(flags);
        Pattern pattern=Pattern.compile(source,flags);
        List<Capture> groups=(flags&Pattern.LITERAL)!=0?Collections.emptyList()
                :scan(source,flags,pattern.matcher("").groupCount());
        return new RegexPathPattern(pattern,groups);
    }

    /** All declared capture keys, in Java capturing-group order, including optional groups. */
    // @req FR-ROUTE-019 AC-3
    public List<String> keys() {return keys;}

    /** First search match, or null; each invocation owns independent Matcher state. */
    // @req FR-ROUTE-019 AC-2 AC-3 AC-4
    public @Nullable Match match(String rawPath) {
        Matcher matcher=pattern.matcher(Objects.requireNonNull(rawPath,"rawPath"));
        if(!matcher.find()) return null;
        Map<String,List<String>> captures=new LinkedHashMap<>();
        for(Capture group:groups) {
            String value=matcher.group(group.index);
            if(value!=null) captures.put(group.key,Collections.singletonList(value));
        }
        return new Match(matcher.group(),matcher.start(),matcher.end(),captures);
    }

    /** Immutable raw match snapshot. Nonparticipating captures are absent; empty captures remain present. */
    public static final class Match {
        private final String matchedPath;
        private final int start;
        private final int end;
        private final Map<String,List<String>> captures;
        private Match(String matchedPath,int start,int end,Map<String,List<String>> captures) {
            this.matchedPath=matchedPath;this.start=start;this.end=end;
            this.captures=Collections.unmodifiableMap(new LinkedHashMap<>(captures));
        }
        public String matchedPath() {return matchedPath;}
        public int start() {return start;}
        public int end() {return end;}
        public Map<String,List<String>> captures() {return captures;}
    }

    private static void validateFlags(int flags) {
        // Java8 Pattern accepts unknown bits that newer JDKs reject. Keep registration behavior portable.
        if((flags&~0x1ff)!=0)throw new IllegalArgumentException("Unknown Java Pattern flags: "+flags);
    }
    private static final class Capture {
        final String key;final int index;
        Capture(String key,int index) {this.key=key;this.index=index;}
    }
    private static boolean sameGroups(List<Capture> a,List<Capture> b) {
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(a.get(i).index!=b.get(i).index || !a.get(i).key.equals(b.get(i).key))return false;
        return true;
    }
    private static IllegalArgumentException ambiguous() {
        return new IllegalArgumentException("Ambiguous capture metadata in precompiled Pattern; use compile(String source, int flags) with the original flags");
    }
    private static List<Capture> scan(String source,int flags,int count) {
        List<Capture> groups=new Scanner(source,flags).scan();
        if(groups.size()!=count) throw new IllegalArgumentException("Java capture metadata does not match Pattern group count; use compile(String source, int flags) with the original flags");
        return groups;
    }

    /** Lexical metadata only; Java Pattern remains the parser, validator and matching engine. */
    private static final class Scanner {
        private final String source;
        private int flags;
        private int cursor;
        private int unnamed;
        private boolean quoted;
        private final List<Capture> groups=new ArrayList<>();
        private final Deque<Integer> groupScopes=new ArrayDeque<>();
        private final Deque<ClassState> classes=new ArrayDeque<>();
        Scanner(String source,int flags) {this.source=source;this.flags=flags;}
        List<Capture> scan() {
            while(cursor<source.length()) {
                char c=source.charAt(cursor);
                if(quoted) {
                    if(c=='\\' && at(cursor+1,'E')) {quoted=false;cursor+=2;}
                    else {classContent();cursor++;}
                    continue;
                }
                cursor=skipIgnored(cursor,flags);
                if(cursor==source.length())break;
                c=source.charAt(cursor);
                if(c=='\\') {escape();continue;}
                if(!classes.isEmpty()) {characterClass(c);continue;}
                if(c=='[') {classes.push(new ClassState());cursor++;continue;}
                if(c=='(') {group();continue;}
                if(c==')') {
                    if(groupScopes.isEmpty())throw metadataError();
                    flags=groupScopes.pop();cursor++;continue;
                }
                cursor++;
            }
            if(!classes.isEmpty() || !groupScopes.isEmpty())throw metadataError();
            return groups;
        }
        private boolean at(int index,char expected) {return index<source.length() && source.charAt(index)==expected;}
        private void classContent() {if(!classes.isEmpty())classes.peek().first=false;}
        private void escape() {
            if(at(cursor+1,'Q')) {quoted=true;cursor+=2;return;}
            classContent();
            if(at(cursor+1,'c')) {cursor=skipIgnored(cursor+2,flags);if(cursor<source.length())cursor++;}
            else cursor=Math.min(cursor+2,source.length());
        }
        private void characterClass(char c) {
            ClassState state=classes.peek();
            if(c=='^' && state.first && !state.negated)state.negated=true;
            else if(c==']' && !state.first)classes.pop();
            else if(c=='[') {state.first=false;classes.push(new ClassState());}
            else state.first=false;
            cursor++;
        }
        private void capture(@Nullable String name) {
            groups.add(new Capture(name==null?String.valueOf(unnamed++):name,groups.size()+1));
        }
        private void group() {
            int next=skipIgnored(cursor+1,flags);
            if(next>=source.length() || source.charAt(next)!='?') {
                capture(null);groupScopes.push(flags);cursor++;return;
            }
            int marker=next+1;
            if(marker>=source.length())throw metadataError();
            char kind=source.charAt(marker);
            if(kind==':' || kind=='=' || kind=='!' || kind=='>') {
                groupScopes.push(flags);cursor=marker+1;return;
            }
            if(kind=='<') {
                int nameStart=skipIgnored(marker+1,flags);
                if(nameStart>=source.length())throw metadataError();
                char first=source.charAt(nameStart);
                if(first=='=' || first=='!') {groupScopes.push(flags);cursor=nameStart+1;return;}
                StringBuilder name=new StringBuilder();int i=nameStart;
                for(;;) {
                    i=skipIgnored(i,flags);
                    if(i>=source.length())throw metadataError();
                    char letter=source.charAt(i++);
                    if(letter=='>')break;
                    name.append(letter);
                }
                capture(name.toString());groupScopes.push(flags);cursor=i;return;
            }
            int local=flags;boolean remove=false;int i=marker;
            for(;;) {
                i=skipIgnored(i,local);
                if(i>=source.length())throw metadataError();
                char flag=source.charAt(i++);
                if(flag==')') {flags=local;cursor=i;return;}
                if(flag==':') {groupScopes.push(flags);flags=local;cursor=i;return;}
                if(flag=='-') {remove=true;continue;}
                int bit=flagBit(flag);
                if(bit==0)throw metadataError();
                local=remove?local&~bit:local|bit;
            }
        }
        private int skipIgnored(int index,int currentFlags) {
            if((currentFlags&Pattern.COMMENTS)==0)return index;
            while(index<source.length()) {
                char c=source.charAt(index);
                if(c==' ' || c>='\t' && c<='\r') {index++;continue;}
                if(c!='#')break;
                index++;
                while(index<source.length()) {
                    c=source.charAt(index);
                    if(c==0 || c=='\n' || (currentFlags&Pattern.UNIX_LINES)==0
                            && (c=='\r' || c=='\u0085' || c=='\u2028' || c=='\u2029'))break;
                    index++;
                }
            }
            return index;
        }
        private static int flagBit(char flag) {
            switch(flag) {
                case 'i':return Pattern.CASE_INSENSITIVE;
                case 'd':return Pattern.UNIX_LINES;
                case 'm':return Pattern.MULTILINE;
                case 's':return Pattern.DOTALL;
                case 'u':return Pattern.UNICODE_CASE;
                case 'x':return Pattern.COMMENTS;
                case 'c':return Pattern.CANON_EQ;
                // Java 7's U flag implies Unicode case and character classes; numeric bits avoid an API24 field reference.
                case 'U':return 0x100|Pattern.UNICODE_CASE;
                default:return 0;
            }
        }
        private IllegalArgumentException metadataError() {return new IllegalArgumentException("Cannot inspect Java Pattern capture metadata near source index "+cursor);}
    }
    private static final class ClassState {boolean first=true;boolean negated;}
}
