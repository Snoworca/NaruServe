package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.common.AbortListeners;
import com.snoworca.naruserve.spi.*;
import com.snoworca.naruserve.testing.RecordingExchange;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Observable detected-abort SPI fixture; normal finish intentionally never signals abort. */
// @req IR-HTTP-009 AC-1 AC-4 AC-5 AC-6
final class LifecycleExchange implements HttpExchange {
    final RecordingExchange recording;
    final CountingInput input;
    final AbortListeners aborts=new AbortListeners();
    final AtomicBoolean transportAborted=new AtomicBoolean();
    final AtomicInteger subscriptions=new AtomicInteger();
    String query;
    boolean abortOnSubscribe;
    LifecycleExchange(String method,String path,byte[] body,String... headers) {
        recording=new RecordingExchange(method,path,headers);input=new CountingInput(body);
    }
    LifecycleExchange(String method,String path) {this(method,path,new byte[0]);}
    public String methodToken(){return recording.methodToken();}
    public String rawPath(){return recording.rawPath();}
    public String rawQuery(){return query;}
    public String httpVersion(){return recording.httpVersion();}
    public List<Map.Entry<String,String>> headers(){return recording.headers();}
    public InputStream requestBody(){return input;}
    public String remoteAddress(){return recording.remoteAddress();}
    public boolean isSecure(){return false;}
    public OutputStream commit(int status,List<Map.Entry<String,String>> headers,long length){return recording.commit(status,headers,length);}
    public void finish() throws IOException {recording.finish();}
    public void abort(){if(transportAborted.compareAndSet(false,true)){recording.abort();aborts.abort();}}
    public void onAbort(Runnable listener){subscriptions.incrementAndGet();aborts.onAbort(listener);if(abortOnSubscribe)abort();}
    public void sendFile(int status,List<Map.Entry<String,String>> headers,File file,long offset,long length){recording.sendFile(status,headers,file,offset,length);}
    public void upgrade(List<Map.Entry<String,String>> headers,UpgradeHandler handler){recording.upgrade(headers,handler);}
    public void eventStream(int status,List<Map.Entry<String,String>> headers,StreamDrain drain)throws IOException{recording.eventStream(status,headers,drain);}
    static final class CountingInput extends InputStream {
        final ByteArrayInputStream source;final AtomicInteger closes=new AtomicInteger();volatile boolean closed;
        IOException closeFailure;
        CountingInput(byte[] bytes){source=new ByteArrayInputStream(bytes);}
        public int read()throws IOException{if(closed)throw new IOException("input closed");return source.read();}
        public int read(byte[] bytes,int offset,int length)throws IOException{if(closed)throw new IOException("input closed");return source.read(bytes,offset,length);}
        public void close()throws IOException{closes.incrementAndGet();closed=true;if(closeFailure!=null)throw closeFailure;}
    }
}
