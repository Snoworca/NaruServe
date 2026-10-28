package com.snoworca.naruserve.parity;

import com.google.gson.*;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.routing.RoutingConfigException;

/** OPS-ROUTE-004 AC-7: explicit error-family counterparts; the corpus comparator remains strict. */
final class LanguageErrorCounterparts {
    private static final String HELP="; visit https://git.new/pathToRegexpError for info";
    static void compare(String id,JsonObject reference,RuntimeReplay.Observation actual) {
        try {
            JsonObject expected=reference.deepCopy();
            JsonElement rawRegistration=actual.value.getAsJsonObject("registration").get("error");
            if(actual.registrationFailure!=null) {
                verifyRaw(id,"$.registration.error",rawRegistration,actual.registrationFailure);
                JsonElement expectedError=expected.getAsJsonObject("registration").get("error");
                if(expectedError!=null&&!expectedError.isJsonNull()) {
                    boolean allowed=(id.equals("registration.unnamed-wildcard")||id.equals("registration.inline-regex"))
                            &&actual.registrationFailure.getClass()==RoutingConfigException.class;
                    rename(id,"$.registration.error",expectedError.getAsJsonObject(),actual.registrationFailure,allowed,"TypeError");
                    JsonObject e=expectedError.getAsJsonObject();String message=e.get("message").getAsString();
                    String javaMessage=actual.registrationFailure.getMessage();
                    if(message.endsWith(HELP)&&(javaMessage==null||!javaMessage.endsWith(HELP)))
                        e.addProperty("message",message.substring(0,message.length()-HELP.length()));
                }
            } else if(rawRegistration!=null&&!rawRegistration.isJsonNull()) fail(id,"$.registration.error","error JSON without observed Throwable");
            JsonElement rawObservation=actual.value.get("observation");
            if(rawObservation!=null&&!rawObservation.isJsonNull()) {
                JsonArray rawErrors=rawObservation.getAsJsonObject().getAsJsonObject("completion").getAsJsonArray("errors");
                if(rawErrors.size()!=actual.errors.size())fail(id,"$.observation.completion.errors","raw/Throwable count mismatch");
                JsonElement expectedObservation=expected.get("observation");
                JsonArray expectedErrors=expectedObservation==null||expectedObservation.isJsonNull()?new JsonArray():
                        expectedObservation.getAsJsonObject().getAsJsonObject("completion").getAsJsonArray("errors");
                for(int i=0;i<actual.errors.size();i++) {
                    Throwable error=actual.errors.get(i);String path="$.observation.completion.errors["+i+"]";
                    verifyRaw(id,path,rawErrors.get(i),error);
                    if(i<expectedErrors.size()) {
                        boolean plain=(id.equals("error.thrown")||id.equals("async.rejected"))&&error.getClass()==RuntimeException.class;
                        boolean decode=id.equals("raw.malformed-escape")&&error instanceof HttpException&&((HttpException)error).status()==400
                                &&error.getMessage()!=null&&error.getMessage().startsWith("Failed to decode param '");
                        rename(id,path,expectedErrors.get(i).getAsJsonObject(),error,plain||decode,decode?"URIError":"Error");
                    }
                }
            } else if(!actual.errors.isEmpty())fail(id,"$.observation.completion.errors","Throwable without observation");
            ParityCorpus.compare(id,expected,actual.value);
        } catch(AssertionError failure) {
            throw new AssertionError(failure.getMessage()+"\nraw reference="+reference+"\nraw Java="+actual.value
                    +"\nraw Java registration Throwable="+actual.registrationFailure+"\nraw Java handled Throwables="+actual.errors,failure);
        }
    }
    private static void verifyRaw(String id,String path,JsonElement json,Throwable error) {
        if(json==null||!json.isJsonObject())fail(id,path,"missing raw error JSON");
        JsonObject object=json.getAsJsonObject();
        if(!new JsonPrimitive(error.getClass().getName()).equals(object.get("name")))fail(id,path+".name","raw Java name differs from Throwable");
        JsonElement message=error.getMessage()==null?JsonNull.INSTANCE:new JsonPrimitive(error.getMessage());
        if(!message.equals(object.get("message")))fail(id,path+".message","raw Java message differs from Throwable");
    }
    private static void rename(String id,String path,JsonObject expected,Throwable actual,boolean allowed,String referenceFamily) {
        if(!allowed||!new JsonPrimitive(referenceFamily).equals(expected.get("name")))fail(id,path+".name","unapproved language error family "+actual.getClass().getName());
        expected.addProperty("name",actual.getClass().getName());
    }
    private static void fail(String id,String field,String reason){throw new AssertionError(id+" "+field+": "+reason);}
}
