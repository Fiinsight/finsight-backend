package com.finsight.news.collect;

import java.io.InputStream;
import java.io.FilterInputStream;
import java.net.*;
import java.time.Duration;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;

/** Reads bounded image headers in memory; never saves or rehosts publisher image files. */
@Component
public class NewsImageVerifier {
    private final com.github.benmanes.caffeine.cache.Cache<String,Boolean> cache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(512).expireAfterWrite(Duration.ofMinutes(30)).build();

    public String verify(String value) {
        String url=NewsImageUrl.sanitize(value,"");
        return url != null && Boolean.TRUE.equals(cache.get(url,this::probe)) ? url : null;
    }
    private boolean probe(String value) {
        try {
            URI uri=URI.create(value);
            for(var address:InetAddress.getAllByName(uri.getHost())) {
                if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress()) return false;
            }
            var connection=(HttpURLConnection)uri.toURL().openConnection();
            connection.setConnectTimeout(2000);connection.setReadTimeout(2000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","Mozilla/5.0 (compatible; FinsightBot/1.0)");
            try {
                if(connection.getResponseCode()!=200) return false;
                try(var stream=connection.getInputStream()) { return usableDimensions(stream); }
            } finally { connection.disconnect(); }
        } catch(Exception unavailable) { return false; }
    }
    static boolean usableDimensions(InputStream source) {
        try(var bounded=new FilterInputStream(source) {
            private int remaining=65536;
            @Override public int read() throws java.io.IOException {if(remaining<=0)return -1;int n=super.read();if(n>=0)remaining--;return n;}
            @Override public int read(byte[] bytes,int offset,int length) throws java.io.IOException {if(remaining<=0)return -1;int n=in.read(bytes,offset,Math.min(length,remaining));if(n>0)remaining-=n;return n;}
        }; var image=new MemoryCacheImageInputStream(bounded)) {
            var readers=ImageIO.getImageReaders(image);
            if(!readers.hasNext()) return false;
            var reader=readers.next();
            try {reader.setInput(image);return reader.getWidth(0)>1 && reader.getHeight(0)>1;}
            finally {reader.dispose();}
        } catch(Exception invalid) {return false;}
    }
}
