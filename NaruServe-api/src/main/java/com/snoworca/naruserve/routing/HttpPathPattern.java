package com.snoworca.naruserve.routing;

import org.jspecify.annotations.Nullable;
import java.util.*;

/**
 * Engine-free HTTP string-path matcher with the path-to-regexp8.4.2 grammar.
 * Inputs and captures are raw pathnames: this class neither decodes nor normalizes them.
 * Compiled instances and their results are immutable and may be shared between requests.
 * This class does not change the legacy {@link RoutePattern} or WebSocket routing profile.
 *
 * <p>The parser, optional expansion and guarded capture choices derive from the MIT
 * licensed path-to-regexp8.4.2. See the packaged HTTP-PATH-LICENSE.txt. An ordered,
 * iterative NFA executes those choices without recursive regular-expression backtracking.
 */
// @req FR-ROUTE-018 AC-1 AC-2 AC-3 AC-4 AC-5 AC-6
public final class HttpPathPattern {
    private final Node entry;
    private final int nodeCount;
    private final List<Key> captureKeys;
    private final List<Key> keys;
    private final boolean caseSensitive;

    private HttpPathPattern(Node entry, int nodeCount, List<Key> captureKeys,
                            List<Key> keys, boolean caseSensitive) {
        this.entry=entry; this.nodeCount=nodeCount; this.captureKeys=captureKeys;
        this.keys=Collections.unmodifiableList(keys); this.caseSensitive=caseSensitive;
    }

    /** Compile a raw string path. All options are explicit; '/' is the delimiter.
     * @param end true for full matching, false for delimiter-bounded prefix matching
     * @param caseSensitive true for exact literals, false for ECMAScript non-u case folding
     * @param trailing whether one extra trailing slash at the end of the input is allowed
     * @throws RoutingConfigException on invalid grammar or more than256 optional alternatives
     */
    public static HttpPathPattern compile(String path, boolean end, boolean caseSensitive, boolean trailing) {
        Objects.requireNonNull(path,"path");
        List<Token> tokens=new Parser(path).parse();
        List<List<Token>> alternatives=expand(tokens,path);
        List<Key> keys=new ArrayList<>();
        Deque<Token> pending=new ArrayDeque<>();
        for(int i=tokens.size()-1;i>=0;i--) pending.push(tokens.get(i));
        while(!pending.isEmpty()) {
            Token token=pending.pop();
            if(token.type==GROUP) for(int i=token.children.size()-1;i>=0;i--) pending.push(token.children.get(i));
            else if(token.type!=TEXT) keys.add(new Key(token.value,token.type==WILDCARD));
        }
        Builder builder=new Builder(path);
        Node terminal=builder.node(MATCH,null,null,null,-1);
        Node suffix=end?builder.node(END,terminal,null,null,-1):builder.node(BOUNDARY,terminal,null,null,-1);
        if(trailing) {
            Node slash=builder.literal("/",builder.node(END,suffix,null,null,-1));
            suffix=builder.node(SPLIT,slash,suffix,null,-1);
        }
        List<Key> captures=new ArrayList<>();
        List<List<Piece>> compiled=new ArrayList<>();
        for(List<Token> alternative:alternatives) compiled.add(builder.pieces(alternative,captures));
        Node entry=null;
        for(int i=compiled.size()-1;i>=0;i--) {
            Node branch=builder.sequence(compiled.get(i),suffix);
            entry=entry==null?branch:builder.node(SPLIT,branch,entry,null,-1);
        }
        return new HttpPathPattern(Objects.requireNonNull(entry),builder.count,
                Collections.unmodifiableList(captures),keys,caseSensitive);
    }

    /** Grammar-order key metadata, including keys inside optional groups and duplicates. */
    public List<Key> keys() { return keys; }

    /** Return a match, or null. Wildcards split on literal '/' before any later decoding. */
    public @Nullable Match match(String rawPath) {
        Objects.requireNonNull(rawPath,"rawPath");
        int[] visited=new int[nodeCount];
        List<ThreadState> current=new ArrayList<>(), next=new ArrayList<>();
        int generation=1;
        closure(entry,null,0,rawPath,current,visited,generation);
        History best=null;
        int bestEnd=-1;
        for(int position=0;!current.isEmpty();position++) {
            next.clear();
            generation++;
            for(ThreadState state:current) {
                if(state.node.type==MATCH) { best=state.history; bestEnd=position; break; }
                if(position<rawPath.length() && state.node.guard.accept(rawPath,position,caseSensitive))
                    closure(state.node.first,state.history,position+1,rawPath,next,visited,generation);
            }
            List<ThreadState> swap=current; current=next; next=swap;
        }
        if(bestEnd<0) return null;
        int[] offsets=new int[captureKeys.size()*2];
        Arrays.fill(offsets,-1);
        for(History h=best;h!=null;h=h.previous) if(offsets[h.slot]<0) offsets[h.slot]=h.position;
        Map<String,List<String>> values=new LinkedHashMap<>();
        Set<String> wildcards=new LinkedHashSet<>();
        for(int i=0;i<captureKeys.size();i++) {
            if(offsets[i*2]<0 || offsets[i*2+1]<0) continue;
            Key key=captureKeys.get(i);
            String value=rawPath.substring(offsets[i*2],offsets[i*2+1]);
            List<String> parts=key.wildcard?Arrays.asList(value.split("/",-1)):Collections.singletonList(value);
            values.put(key.name,Collections.unmodifiableList(new ArrayList<>(parts)));
            if(key.wildcard) wildcards.add(key.name); else wildcards.remove(key.name);
        }
        return new Match(rawPath.substring(0,bestEnd),Collections.unmodifiableMap(values),Collections.unmodifiableSet(wildcards));
    }

    /* Ordered epsilon closure. First arrival at a node wins, retaining the same
       alternative/greedy priority as a backtracking matcher without revisiting states. */
    private static void closure(Node start, @Nullable History history, int position, String input,
                                List<ThreadState> output, int[] visited, int generation) {
        Deque<ThreadState> stack=new ArrayDeque<>();
        stack.push(new ThreadState(start,history));
        while(!stack.isEmpty()) {
            ThreadState state=stack.pop(); Node n=state.node;
            if(visited[n.id]==generation) continue;
            visited[n.id]=generation;
            switch(n.type) {
                case SPLIT:
                    stack.push(new ThreadState(n.second,state.history));
                    stack.push(new ThreadState(n.first,state.history)); break;
                case SAVE:
                    stack.push(new ThreadState(n.first,new History(n.slot,position,state.history))); break;
                case END:
                    if(position==input.length()) stack.push(new ThreadState(n.first,state.history)); break;
                case BOUNDARY:
                    if(position==input.length() || input.charAt(position)=='/') stack.push(new ThreadState(n.first,state.history)); break;
                default: output.add(state);
            }
        }
    }

    public static final class Match {
        private final String matchedPath;
        private final Map<String,List<String>> captures;
        private final Set<String> wildcards;
        private Match(String matchedPath, Map<String,List<String>> captures, Set<String> wildcards) {
            this.matchedPath=matchedPath; this.captures=captures; this.wildcards=wildcards;
        }
        public String matchedPath() { return matchedPath; }
        public Map<String,List<String>> captures() { return captures; }
        /** Names whose last participating binding was a wildcard, even if it has one segment.
         * A later scalar duplicate removes the name; an absent optional does not overwrite it.
         */
        // @req FR-HTTP-027 AC-1
        public Set<String> wildcards() { return wildcards; }
    }
    public static final class Key {
        private final String name;
        private final boolean wildcard;
        private Key(String name, boolean wildcard) { this.name=name; this.wildcard=wildcard; }
        public String name() { return name; }
        public boolean wildcard() { return wildcard; }
    }

    private static final int TEXT=0, PARAM=1, WILDCARD=2, GROUP=3;
    private static final int CHAR=0, SPLIT=1, SAVE=2, END=3, BOUNDARY=4, MATCH=5;
    private static final class Token {
        final int type, index;
        final String value;
        final List<Token> children;
        Token(int type, String value, int index) { this.type=type; this.value=value; this.index=index; this.children=new ArrayList<>(); }
    }
    private static final class Parser {
        final String source;
        final int[] chars;
        int index;
        Parser(String source) { this.source=source; this.chars=codePoints(source); }
        List<Token> parse() {
            List<Token> root=new ArrayList<>(), current=root;
            Deque<List<Token>> parents=new ArrayDeque<>();
            StringBuilder text=new StringBuilder(); int textIndex=0;
            while(index<chars.length) {
                int value=chars[index++], at=index-1;
                if(value=='\\') {
                    if(index==chars.length) throw error("Unexpected end after \\",index,source);
                    if(text.length()==0) textIndex=at;
                    text.appendCodePoint(chars[index++]); continue;
                }
                if(value==':' || value=='*') {
                    flush(current,text,textIndex);
                    StringBuilder name=new StringBuilder();
                    if(index<chars.length && HttpPathUnicode.start(chars[index])) {
                        do { name.appendCodePoint(chars[index++]); }
                        while(index<chars.length && HttpPathUnicode.part(chars[index]));
                    } else if(index<chars.length && chars[index]=='"') {
                        int quote=index++; boolean closed=false;
                        while(index<chars.length) {
                            int c=chars[index++];
                            if(c=='"') { closed=true; break; }
                            if(c=='\\') {
                                if(index==chars.length) break;
                                c=chars[index++];
                            }
                            name.appendCodePoint(c);
                        }
                        if(!closed) throw error("Unterminated quote",quote,source);
                    }
                    if(name.length()==0) throw error("Missing parameter name",index,source);
                    current.add(new Token(value==':'?PARAM:WILDCARD,name.toString(),at)); continue;
                }
                if(value=='{') {
                    flush(current,text,textIndex);
                    Token group=new Token(GROUP,"",at); current.add(group);
                    parents.push(current); current=group.children; continue;
                }
                if(value=='}') {
                    if(parents.isEmpty()) throw error("Unexpected }",at,source);
                    flush(current,text,textIndex); current=parents.pop(); continue;
                }
                if("()[]+?!".indexOf(value)>=0) throw error("Unexpected "+new String(Character.toChars(value)),at,source);
                if(text.length()==0) textIndex=at;
                text.appendCodePoint(value);
            }
            if(!parents.isEmpty()) throw error("Unexpected end, expected }",index,source);
            flush(current,text,textIndex); return root;
        }
        private static void flush(List<Token> output,StringBuilder text,int at) {
            if(text.length()>0) { output.add(new Token(TEXT,text.toString(),at)); text.setLength(0); }
        }
        private static int[] codePoints(String s) {
            int[] result=new int[s.codePointCount(0,s.length())];
            int n=0;
            for(int i=0;i<s.length();) { int cp=s.codePointAt(i); result[n++]=cp; i+=Character.charCount(cp); }
            return result;
        }
    }
    private static RoutingConfigException error(String message,int index,String path) {
        return new RoutingConfigException(message+" at index "+index+": "+path);
    }

    private static final class Work {
        final Token token; final @Nullable Work next;
        Work(Token token,@Nullable Work next) { this.token=token; this.next=next; }
    }
    private static @Nullable Work prepend(List<Token> tokens,@Nullable Work next) {
        for(int i=tokens.size()-1;i>=0;i--) next=new Work(tokens.get(i),next);
        return next;
    }
    private static final class Expansion {
        final @Nullable Work work; final List<Token> prefix;
        Expansion(@Nullable Work work,List<Token> prefix) { this.work=work; this.prefix=prefix; }
    }
    private static List<List<Token>> expand(List<Token> tokens,String path) {
        List<List<Token>> result=new ArrayList<>();
        Deque<Expansion> stack=new ArrayDeque<>();
        stack.push(new Expansion(prepend(tokens,null),new ArrayList<>()));
        while(!stack.isEmpty()) {
            Expansion state=stack.pop(); Work work=state.work; List<Token> flat=state.prefix;
            while(work!=null) {
                Token token=work.token;
                if(token.type==GROUP) {
                    stack.push(new Expansion(work.next,new ArrayList<>(flat)));
                    work=prepend(token.children,work.next);
                } else { flat.add(token); work=work.next; }
            }
            if(result.size()==256) throw error("Too many path combinations",path.codePointCount(0,path.length()),path);
            result.add(flat);
        }
        return result;
    }

    private interface Guard { boolean accept(String value,int offset,boolean sensitive); }
    private static boolean starts(String input,int offset,String text,boolean sensitive) {
        if(input.length()-offset<text.length()) return false;
        for(int i=0;i<text.length();i++) {
            char a=input.charAt(offset+i), b=text.charAt(i);
            if(sensitive?a!=b:HttpPathUnicode.fold(a)!=HttpPathUnicode.fold(b)) return false;
        }
        return true;
    }
    private static Guard negate(String a,String b) {
        return (s,p,c)->(a.isEmpty() || !starts(s,p,a,c)) && (b.isEmpty() || !starts(s,p,b,c));
    }
    private static final Guard ANY=(s,p,c)->true;
    private static final class Node {
        final int id,type,slot;
        Node first,second;
        final Guard guard;
        Node(int id,int type,Node first,Node second,Guard guard,int slot) {
            this.id=id; this.type=type; this.first=first; this.second=second; this.guard=guard; this.slot=slot;
        }
    }
    private static final class History {
        final int slot,position; final @Nullable History previous;
        History(int slot,int position,@Nullable History previous) { this.slot=slot; this.position=position; this.previous=previous; }
    }
    private static final class ThreadState {
        final Node node; final @Nullable History history;
        ThreadState(Node node,@Nullable History history) { this.node=node; this.history=history; }
    }
    private static final class Piece {
        final String text;
        final @Nullable Guard guard;
        final @Nullable String alternate;
        final int key;
        final @Nullable Guard fallback;
        Piece(String text,@Nullable Guard guard,@Nullable String alternate,int key) { this.text=text; this.guard=guard; this.alternate=alternate; this.key=key; this.fallback=null; }
        Piece(Piece p,Guard fallback) { this.text=p.text; this.guard=p.guard; this.alternate=p.alternate; this.key=p.key; this.fallback=fallback; }
    }
    private static final class Builder {
        final String path; int count;
        Builder(String path) { this.path=path; }
        Node node(int type,@Nullable Node first,@Nullable Node second,@Nullable Guard guard,int slot) {
            return new Node(count++,type,first,second,guard,slot);
        }
        Node literal(String text,Node next) {
            for(int i=text.length()-1;i>=0;i--) {
                final char value=text.charAt(i);
                next=node(CHAR,next,null,(s,p,c)->c?s.charAt(p)==value:HttpPathUnicode.fold(s.charAt(p))==HttpPathUnicode.fold(value),-1);
            }
            return next;
        }
        Node plus(Guard guard,Node next) {
            Node split=node(SPLIT,null,next,null,-1);
            Node consume=node(CHAR,split,null,guard,-1);
            split.first=consume; return consume;
        }
        Node sequence(List<Piece> pieces,Node next) {
            for(int i=pieces.size()-1;i>=0;i--) {
                Piece p=pieces.get(i);
                if(p.key<0) { next=literal(p.text,next); continue; }
                Node finish=node(SAVE,next,null,null,p.key*2+1);
                Node capture=plus(Objects.requireNonNull(p.guard),finish);
                if(p.fallback!=null) capture=node(SPLIT,capture,plus(p.fallback,finish),null,-1);
                if(p.alternate!=null) capture=node(SPLIT,capture,literal(p.alternate,finish),null,-1);
                next=node(SAVE,capture,null,null,p.key*2);
            }
            return next;
        }
        List<Piece> pieces(List<Token> tokens,List<Key> keys) {
            List<Piece> pieces=new ArrayList<>();
            String backtrack="", wildcardBacktrack=""; int previous=0, inSegment=0;
            for(int i=0;i<tokens.size();i++) {
                Token token=tokens.get(i);
                if(token.type==TEXT) {
                    pieces.add(new Piece(token.value,null,null,-1)); backtrack+=token.value;
                    if(previous==WILDCARD) wildcardBacktrack+=token.value;
                    if(token.value.indexOf('/')>=0) inSegment=0;
                    continue;
                }
                if(previous!=0 && backtrack.isEmpty()) throw error("Missing text before \""+token.value+"\" capture",token.index,path);
                Guard guard; String alternate=null;
                if(token.type==PARAM) {
                    if((inSegment&WILDCARD)!=0) guard=negate("/",backtrack);
                    else if(hasWildcard(tokens,i+1)) guard=negate("/",peekText(tokens,i+1));
                    else if((inSegment&PARAM)!=0) { guard=negate("/",backtrack); alternate=backtrack; }
                    else guard=negate("/","");
                } else {
                    if((inSegment&WILDCARD)!=0) guard=negate(backtrack,"");
                    else if(!wildcardBacktrack.isEmpty()) {
                        // The second upstream alternative is itself a greedy '/'
                        // exclusion, not literal text; construct it separately below.
                        guard=negate(wildcardBacktrack,"");
                    } else guard=ANY;
                }
                int key=keys.size(); keys.add(new Key(token.value,token.type==WILDCARD));
                pieces.add(new Piece("",guard,alternate,key));
                // Wildcard fallback uses a separate guarded alternative.
                if(token.type==WILDCARD && (inSegment&WILDCARD)==0 && !wildcardBacktrack.isEmpty()) {
                    Piece old=pieces.remove(pieces.size()-1);
                    pieces.add(new Piece(old,negate("/","")));
                }
                if(token.type==WILDCARD) wildcardBacktrack="";
                inSegment|=(previous=token.type); backtrack="";
            }
            return pieces;
        }
        boolean hasWildcard(List<Token> tokens,int at) {
            for(;at<tokens.size();at++) { Token t=tokens.get(at); if(t.type==WILDCARD)return true;
                if(t.type==TEXT && t.value.indexOf('/')>=0)break; }
            return false;
        }
        String peekText(List<Token> tokens,int at) {
            StringBuilder result=new StringBuilder();
            for(;at<tokens.size() && tokens.get(at).type==TEXT;at++) result.append(tokens.get(at).value);
            return result.toString();
        }
    }
}
