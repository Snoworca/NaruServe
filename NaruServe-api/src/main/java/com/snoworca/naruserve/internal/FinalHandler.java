package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.common.HtmlEscaping;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * Common HTTP finalhandler. Error details use Java Throwable.printStackTrace, normalized to LF,
 * escaped into the same HTML scaffold; no JavaScript stack frames are invented. Successful stack
 * fallthrough uses the original raw pathname, excludes query, and preserves valid percent escapes.
 * Behavior reference: finalhandler/encodeurl (MIT), see HTTP-FINALHANDLER-LICENSE.txt.
 */
// @req FR-APP-024 AC-3 AC-4 AC-5; FR-HTTP-028 AC-3 AC-5
final class FinalHandler {
    private FinalHandler() { }
    static void finish(DefaultRequest request,DefaultResponse response,@Nullable Throwable error,SafeLogger logger) {
        if(error==null && response.state()!=ResponseState.NEW)return;
        if(error!=null)logger.error("request "+request.requestId()+" failed",error);
        if(response.state()!=ResponseState.NEW) {
            if(response.state()!=ResponseState.COMPLETED && response.state()!=ResponseState.ABORTED)response.abort(error);
            return;
        }
        int status;String message;
        if(error==null) {
            status=404;message="Cannot "+request.methodToken()+" "+encodeUrl(request.rawPath());
        }else{
            status=error instanceof HttpException?((HttpException)error).status():response.statusCode();
            if(status<400 || status>599)status=500;
            StringWriter stack=new StringWriter();error.printStackTrace(new PrintWriter(stack));
            message=stack.toString().replace("\r\n","\n");
        }
        response.prepareFinalErrorHeaders(error);
        String escaped=HtmlEscaping.escape(message).replace("\n","<br>").replace("  "," &nbsp;");
        String document="<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<title>Error</title>\n</head>\n<body>\n<pre>"+
                escaped+"</pre>\n</body>\n</html>\n";
        response.status(status).header("Content-Security-Policy","default-src 'none'")
                .header("X-Content-Type-Options","nosniff").header("Content-Type","text/html; charset=utf-8");
        response.send(document.getBytes(StandardCharsets.UTF_8));
    }
    /** Encode only unsafe URL code points; valid escapes are already wire syntax. */
    private static String encodeUrl(String raw) {
        StringBuilder out=new StringBuilder(raw.length());
        for(int i=0;i<raw.length();) {
            int cp=raw.codePointAt(i),width=Character.charCount(cp);
            if(cp=='%' && i+2<raw.length() && hex(raw.charAt(i+1)) && hex(raw.charAt(i+2))) {
                out.append(raw,i,i+3);i+=3;continue;
            }
            if(cp=='!' || cp>='#' && cp<=';' || cp=='=' || cp>='?' && cp<='_' || cp>='a' && cp<='z' || cp=='|' || cp=='~') {
                if(cp!='%')out.appendCodePoint(cp);else out.append("%25");
            }else{
                // encodeurl replaces unmatched surrogate code units with the Unicode replacement character.
                if(cp>=0xD800 && cp<=0xDFFF)cp=0xFFFD;
                out.append(PercentDecoder.percentEncode(new String(Character.toChars(cp)),""));
            }
            i+=width;
        }
        return out.toString();
    }
    private static boolean hex(char value) { return value>='0'&&value<='9'||value>='A'&&value<='F'||value>='a'&&value<='f'; }
}
