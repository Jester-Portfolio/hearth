package io.github.jesterportfolio.hearth.features;

import java.io.*;
import java.util.*;

/** 개인 최초 필수 시청·이후 선택·명시적 스킵. 장/공유/보상 상태를 변경하지 않습니다. */
public final class Cutscene {
    private Cutscene(){}
    public record State(long attempts,boolean completed,boolean skipFuture,UUID request,int frame){public State{if(attempts<0||frame<0||frame>128||skipFuture&&!completed)throw new IllegalArgumentException("SCENE_STATE");}public static State empty(){return new State(0,false,false,null,0);}}
    public enum Choice{WATCH,SKIP,REPLAY}
    public static boolean choiceRequired(State s){return s.completed&&!s.skipFuture;}
    public static State start(State s,UUID request,Choice choice){Objects.requireNonNull(request);if(choice==Choice.REPLAY){if(!s.completed)throw new IllegalStateException("SCENE_FIRST_REQUIRED");return s;}
        if(request.equals(s.request))return s;if(choice==Choice.SKIP&&!s.completed)throw new IllegalStateException("SCENE_FIRST_REQUIRED");
        return new State(Math.incrementExact(s.attempts),s.completed,s.skipFuture||choice==Choice.SKIP,request,0);
    }
    public static State advance(State s,UUID request,int expected,int frames){if(!request.equals(s.request)||s.skipFuture||s.frame!=expected||frames<1||frames>128||expected>=frames)throw new IllegalStateException("STALE_SCENE_FRAME");int next=expected+1;return new State(s.attempts,s.completed||next==frames,s.skipFuture,request,next);}
    public static byte[] encode(State s){try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(1);out.writeLong(s.attempts);out.writeBoolean(s.completed);out.writeBoolean(s.skipFuture);out.writeBoolean(s.request!=null);if(s.request!=null){out.writeLong(s.request.getMostSignificantBits());out.writeLong(s.request.getLeastSignificantBits());}out.writeInt(s.frame);return bytes.toByteArray();}catch(IOException e){throw new IllegalStateException(e);}}
    public static State decode(byte[] bytes){try{var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=1)throw new IOException();var s=new State(in.readLong(),in.readBoolean(),in.readBoolean(),in.readBoolean()?new UUID(in.readLong(),in.readLong()):null,in.readInt());if(in.available()!=0)throw new IOException();return s;}catch(IOException|RuntimeException e){throw new IllegalArgumentException("INVALID_SCENE_STATE");}}
}
