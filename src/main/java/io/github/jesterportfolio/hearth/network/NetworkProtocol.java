package io.github.jesterportfolio.hearth.network;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 버전·도메인을 고정한 HMAC. 클라이언트가 보낸 위장 응답은 성공/해제 경로에 들어가지 않습니다. */
public final class NetworkProtocol {
    public static final String CHANNEL="hearth:network";public static final int LIMIT=512;
    public enum Kind {REQUEST,REJECTED,HOLD}
    public record Frame(UUID network,UUID request,String from,String to,long expiresMillis,Kind kind){
        public Frame{Objects.requireNonNull(network);Objects.requireNonNull(request);Objects.requireNonNull(kind);for(String value:List.of(from,to))if(!value.matches("[a-z][a-z0-9_-]{0,31}"))throw bad();if(from.equals(to)||expiresMillis<0)throw bad();}
    }
    private NetworkProtocol(){}
    private static IllegalArgumentException bad(){return new IllegalArgumentException("NETWORK_MESSAGE_INVALID");}
    private static byte[] sign(byte[] body,byte[] key)throws Exception{if(key.length!=32)throw bad();var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));mac.update("Hearth network v1\0".getBytes(StandardCharsets.US_ASCII));return mac.doFinal(body);}
    public static byte[] encode(Frame f,byte[] key){try{var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(1);out.writeLong(f.network().getMostSignificantBits());out.writeLong(f.network().getLeastSignificantBits());out.writeLong(f.request().getMostSignificantBits());out.writeLong(f.request().getLeastSignificantBits());out.writeUTF(f.from());out.writeUTF(f.to());out.writeLong(f.expiresMillis());out.writeByte(f.kind().ordinal());}byte[] body=bytes.toByteArray();bytes.write(sign(body,key));if(bytes.size()>LIMIT)throw bad();return bytes.toByteArray();}catch(Exception e){throw bad();}}
    public static Frame decode(byte[] data,byte[] key){try{if(data.length<32||data.length>LIMIT)throw bad();byte[] body=Arrays.copyOf(data,data.length-32);if(!MessageDigest.isEqual(sign(body,key),Arrays.copyOfRange(data,body.length,data.length)))throw bad();try(var in=new DataInputStream(new ByteArrayInputStream(body))){if(in.readInt()!=1)throw bad();var network=new UUID(in.readLong(),in.readLong());var request=new UUID(in.readLong(),in.readLong());String from=in.readUTF(),to=in.readUTF();long expires=in.readLong();int kind=in.readUnsignedByte();if(in.available()!=0||kind>=Kind.values().length)throw bad();return new Frame(network,request,from,to,expires,Kind.values()[kind]);}}catch(Exception e){throw bad();}}
    public static Frame response(Frame request,Kind kind){if(kind==Kind.REQUEST)throw bad();return new Frame(request.network(),request.request(),request.from(),request.to(),request.expiresMillis(),kind);}
}
