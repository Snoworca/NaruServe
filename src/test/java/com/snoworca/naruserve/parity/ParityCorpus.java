package com.snoworca.naruserve.parity;

import com.google.gson.*;
import java.util.*;

/** Strict observation comparator shared by the corpus replay and feature-specific runtime adapters. */
public final class ParityCorpus {
    private ParityCorpus() {}

    // OPS-ROUTE-004 AC-2 AC-4
    public static void compare(String caseId, JsonElement expected, JsonElement actual) {
        List<String> differences = new ArrayList<>();
        differences("$", expected, actual, differences);
        if (!differences.isEmpty()) throw new AssertionError("Parity case " + caseId + ":\n" + String.join("\n", differences));
    }

    private static void differences(String path, JsonElement expected, JsonElement actual, List<String> out) {
        if (expected == null || actual == null) {
            if (expected != actual) out.add(path + " expected " + display(expected) + " but was " + display(actual));
        } else if (expected.isJsonObject() && actual.isJsonObject()) {
            JsonObject e=expected.getAsJsonObject(), a=actual.getAsJsonObject();
            Set<String> keys=new TreeSet<>(); keys.addAll(e.keySet()); keys.addAll(a.keySet());
            for(String key:keys) differences(path+"."+key,e.get(key),a.get(key),out);
        } else if (expected.isJsonArray() && actual.isJsonArray()) {
            JsonArray e=expected.getAsJsonArray(), a=actual.getAsJsonArray();
            if(e.size()!=a.size()) out.add(path+".length expected "+e.size()+" but was "+a.size());
            for(int i=0;i<Math.min(e.size(),a.size());i++) differences(path+"["+i+"]",e.get(i),a.get(i),out);
        } else if (!expected.equals(actual)) {
            out.add(path+" expected "+display(expected)+" but was "+display(actual));
        }
    }
    private static String display(JsonElement value) {
        if(value==null) return "<absent>";
        String s=value.toString(); return s.length()>200?s.substring(0,200)+"...":s;
    }

    // OPS-ROUTE-004 AC-1 AC-3 AC-5
    public static void validate(JsonObject corpus, JsonObject reference, String generatorSha256) {
        JsonObject schema=new JsonObject();schema.addProperty("schemaVersion",1);
        JsonObject actualSchema=new JsonObject();actualSchema.add("schemaVersion",corpus.get("schemaVersion"));
        compare("metadata",schema,actualSchema);
        // A separately committed source/package lock makes changing only corpus metadata a failure.
        compare("reference",reference,corpus.get("reference"));
        fixed(reference,"node","22.22.0"); fixed(reference,"express","5.1.0");
        fixed(reference,"router","2.2.0"); fixed(reference,"pathToRegexp","8.4.2");
        validateReferenceHashes(reference);
        if(generatorSha256==null || !generatorSha256.matches("[a-f0-9]{64}")) throw new AssertionError("metadata $.generatorSha256 invalid source hash");
        JsonObject generator=new JsonObject();generator.addProperty("generatorSha256",generatorSha256);
        JsonObject actualGenerator=new JsonObject();actualGenerator.add("generatorSha256",corpus.get("generatorSha256"));
        compare("metadata",generator,actualGenerator);
        JsonObject boundary=new JsonObject();boundary.addProperty("regexEngine","java.util.regex.Pattern");
        boundary.addProperty("ecmascriptRegExpEquivalent",false);boundary.addProperty("jni",false);
        JsonObject expectedBoundary=new JsonObject();expectedBoundary.add("boundary",boundary);
        JsonObject actualBoundary=new JsonObject();actualBoundary.add("boundary",corpus.get("boundary"));
        compare("metadata",expectedBoundary,actualBoundary);
        JsonElement entries=corpus.get("cases");
        if(entries==null || !entries.isJsonArray() || entries.getAsJsonArray().size()==0) throw new AssertionError("metadata $.cases must be a nonempty array");
        Set<String> ids=new HashSet<>();
        for(JsonElement entry:entries.getAsJsonArray()) {
            if(!entry.isJsonObject()) throw new AssertionError("metadata $.cases contains a nonobject");
            JsonObject c=entry.getAsJsonObject(); String id=required(c,"id","case").getAsString();
            if(id.isEmpty() || !ids.add(id)) throw new AssertionError("case "+id+" $.id empty or duplicate");
            required(c,"feature",id); JsonObject registration=required(c,"registration",id).getAsJsonObject();
            required(registration,"success",id); required(registration,"error",id);
            JsonElement request=required(c,"request",id), observation=required(c,"observation",id);
            if(!request.isJsonNull()) {required(request.getAsJsonObject(),"method",id);required(request.getAsJsonObject(),"rawPath",id);}
            if(registration.get("success").getAsBoolean()) {
                if(observation.isJsonNull()) throw new AssertionError("case "+id+" $.observation missing for successful registration");
                JsonObject o=observation.getAsJsonObject();
                for(String name:new String[]{"status","headers","body","trace","completion"}) required(o,name,id);
                JsonObject completion=o.getAsJsonObject("completion");required(completion,"state",id);required(completion,"errors",id);
            } else if(!observation.isJsonNull()) throw new AssertionError("case "+id+" $.observation must be null after registration failure");
        }
    }
    /** RFC 9110 sections 8.3.1/8.3.2: charset names and parameter names are case-insensitive. */
    public static String canonicalHeader(String name,String value) {
        if(value==null || !"content-type".equalsIgnoreCase(name)) return value;
        return value.replaceAll("(?i)(;[ \\t]*)charset=utf-8(?=;|$)","$1charset=utf-8");
    }
    private static void validateReferenceHashes(JsonObject reference) {
        JsonElement packages=reference.get("packages");
        if(packages==null || !packages.isJsonObject()) throw new AssertionError("metadata $.reference.packages missing");
        String[][] requiredPackages={{"express","5.1.0"},{"router","2.2.0"},{"path-to-regexp","8.4.2"}};
        for(String[] expected:requiredPackages) {
            JsonElement value=packages.getAsJsonObject().get(expected[0]);String prefix="$.reference.packages."+expected[0];
            if(value==null || !value.isJsonObject()) throw new AssertionError("metadata "+prefix+" missing");
            JsonObject item=value.getAsJsonObject();
            if(!item.has("version") || !expected[1].equals(item.get("version").getAsString())) throw new AssertionError("metadata "+prefix+".version stale");
            requireHash(item.get("packageSha256"),prefix+".packageSha256");requireHash(item.get("entrySha256"),prefix+".entrySha256");
        }
        JsonElement sources=reference.get("sources");
        if(sources==null || !sources.isJsonObject()) throw new AssertionError("metadata $.reference.sources missing");
        for(String name:new String[]{"express/lib/application.js","router/index.js","router/lib/layer.js","router/lib/route.js","path-to-regexp/dist/index.js"}) requireHash(sources.getAsJsonObject().get(name),"$.reference.sources."+name);
    }
    private static void requireHash(JsonElement value,String path) {
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !value.getAsString().matches("[a-f0-9]{64}")) throw new AssertionError("metadata "+path+" must be a SHA-256 hex string");
    }
    private static JsonElement required(JsonObject object,String name,String id) {
        if(!object.has(name)) throw new AssertionError("case "+id+" missing $."+name);
        return object.get(name);
    }
    private static void fixed(JsonObject reference,String name,String version) {
        if(!reference.has(name) || !version.equals(reference.get(name).getAsString())) throw new AssertionError("metadata $.reference."+name+" must be "+version);
    }
}
